package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;

@DisplayName("LlmWikiPageGenerator Tests")
class LlmWikiPageGeneratorBehaviorTest {

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");

    private StubLlmClient llmClient;
    private LlmWikiPageGenerator generator;

    @BeforeEach
    void setUp() {
        llmClient = new StubLlmClient();
        generator = LlmWikiPageGenerator.builder().llmClient(llmClient).build();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Null llmClient should throw NPE")
        void nullLlmClientThrowsNpe() {
            assertThatThrownBy(() -> LlmWikiPageGenerator.builder().build()).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Valid construction succeeds with defaults")
        void validConstructionSucceeds() {
            final LlmWikiPageGenerator gen = LlmWikiPageGenerator.builder().llmClient(llmClient).build();

            assertThat(gen.getMaxSourceContentLength())
                    .isEqualTo(LlmWikiPageGenerator.DEFAULT_MAX_SOURCE_CONTENT_LENGTH);
            assertThat(gen.getMaxIndexPromptLength()).isEqualTo(LlmWikiPageGenerator.DEFAULT_MAX_INDEX_PROMPT_LENGTH);
        }

        @Test
        @DisplayName("Custom maxSourceContentLength is respected")
        void customMaxSourceContentLength() {
            final LlmWikiPageGenerator gen = LlmWikiPageGenerator.builder().llmClient(llmClient)
                    .maxSourceContentLength(1000).build();

            assertThat(gen.getMaxSourceContentLength()).isEqualTo(1000);
        }

        @Test
        @DisplayName("Invalid maxSourceContentLength throws IAE")
        void invalidMaxSourceContentLength() {
            assertThatThrownBy(() -> LlmWikiPageGenerator.builder().maxSourceContentLength(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Invalid maxIndexPromptLength throws IAE")
        void invalidMaxIndexPromptLength() {
            assertThatThrownBy(() -> LlmWikiPageGenerator.builder().maxIndexPromptLength(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Custom model config is passed to LLM calls")
        void customModelConfigIsUsed() {
            final LlmModel customModel = LlmModel.builder().name("custom-model").temperature(0.3).build();
            final LlmWikiPageGenerator gen = LlmWikiPageGenerator.builder().llmClient(llmClient)
                    .modelConfig(customModel).build();

            llmClient.addResponse("---\ntitle: Test\ntags: []\n---\n\n# Test\nContent.");

            gen.generatePageContent(SCOPE, "/test.md", "# Test\nContent.", List.of());

            assertThat(llmClient.getLastModelConfig()).isNotNull();
            assertThat(llmClient.getLastModelConfig().getName()).isPresent();
            assertThat(llmClient.getLastModelConfig().getName().get()).isEqualTo("custom-model");
        }
    }

    @Nested
    @DisplayName("Page Generation")
    class PageGeneration {

        @Test
        @DisplayName("LLM-generated content is returned")
        void llmContentReturned() {
            final String llmOutput = """
                    ---
                    title: Kubernetes Guide
                    tags: [kubernetes, ops]
                    source: /raw/k8s.md
                    ---

                    # Kubernetes Guide

                    Comprehensive guide to Kubernetes operations.""";
            llmClient.addResponse(llmOutput);

            final String result = generator.generatePageContent(SCOPE, "/raw/k8s.md", "# K8s\nSome content.",
                    List.of("docker.md"));

            assertThat(result).isEqualTo(llmOutput);
            assertThat(llmClient.getCallCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Fallback is used when LLM throws exception")
        void fallbackOnException() {
            llmClient.setShouldFail(true);

            final String result = generator.generatePageContent(SCOPE, "/raw/guide.md", "# Guide\nContent.", List.of());

            assertThat(result).contains("# Guide");
            assertThat(result).contains("source: /raw/guide.md");
        }

        @Test
        @DisplayName("Fallback is used when LLM returns blank content")
        void fallbackOnBlankResponse() {
            llmClient.addResponse("   ");

            final String result = generator.generatePageContent(SCOPE, "/raw/guide.md", "# Guide\nContent.", List.of());

            assertThat(result).contains("# Guide");
            assertThat(result).contains("source: /raw/guide.md");
        }

        @Test
        @DisplayName("Source content is truncated when exceeding max length")
        void sourceContentTruncated() {
            final LlmWikiPageGenerator smallGen = LlmWikiPageGenerator.builder().llmClient(llmClient)
                    .maxSourceContentLength(50).build();

            llmClient.addResponse("---\ntitle: Test\ntags: []\n---\n\n# Test\nSummary.");

            final String longContent = "x".repeat(200);
            smallGen.generatePageContent(SCOPE, "/raw/big.md", longContent, List.of());

            // The user message sent to LLM should contain truncated content
            final String lastMessage = llmClient.getLastUserMessage();
            assertThat(lastMessage).contains("[... content truncated at 50 characters]");
            assertThat(lastMessage).doesNotContain("x".repeat(200));
        }

        @Test
        @DisplayName("Fallback also truncates long source content")
        void fallbackTruncatesLongContent() {
            llmClient.setShouldFail(true);

            final String longContent = "x".repeat(LlmWikiPageGenerator.DEFAULT_MAX_SOURCE_CONTENT_LENGTH + 1000);
            final String result = generator.generatePageContent(SCOPE, "/raw/big.md", longContent, List.of());

            assertThat(result).contains("[... content truncated]");
            assertThat(result.length()).isLessThan(longContent.length());
        }

        @Test
        @DisplayName("Existing page names are included in LLM prompt")
        void existingPageNamesIncluded() {
            llmClient.addResponse("---\ntitle: Test\ntags: []\n---\n\nContent.");

            generator.generatePageContent(SCOPE, "/raw/test.md", "# Test", List.of("docker.md", "kubernetes.md"));

            final String lastMessage = llmClient.getLastUserMessage();
            assertThat(lastMessage).contains("docker.md");
            assertThat(lastMessage).contains("kubernetes.md");
        }

        @Test
        @DisplayName("Custom system prompt is used")
        void customSystemPrompt() {
            final String customPrompt = "You are a custom wiki bot.";
            final LlmWikiPageGenerator customGen = LlmWikiPageGenerator.builder().llmClient(llmClient)
                    .pageSystemPrompt(customPrompt).build();

            llmClient.addResponse("---\ntitle: Test\ntags: []\n---\nContent.");

            customGen.generatePageContent(SCOPE, "/raw/test.md", "Test", List.of());

            assertThat(llmClient.getLastSystemPrompt()).isEqualTo(customPrompt);
        }

        @Test
        @DisplayName("Page generation metadata carries component/feature defaults and wiki scope tags")
        void pageGenerationMetadataHasScopeTags() {
            llmClient.addResponse("---\ntitle: T\ntags: []\n---\nBody.");

            generator.generatePageContent(SCOPE, "/raw/t.md", "content", List.of());

            final LlmCallMetadata md = llmClient.getLastMetadata();
            assertThat(md).isNotNull();
            assertThat(md.getComponent()).contains("wiki-generator");
            assertThat(md.getFeature()).contains("page-generation");
            assertThat(md.getTags()).containsEntry("wiki.agent", "ops-agent").containsEntry("wiki.context", "ctx-1")
                    .containsEntry("wiki.name", "runbook");
        }
    }

    @Nested
    @DisplayName("Index Generation")
    class IndexGeneration {

        @Test
        @DisplayName("Empty page list returns static message")
        void emptyPagesReturnsStatic() {
            final String result = generator.generateIndexContent(SCOPE, "agent/ctx/wiki", List.of());

            assertThat(result).contains("No pages yet");
            assertThat(llmClient.getCallCount()).isZero();
        }

        @Test
        @DisplayName("LLM-generated index is returned")
        void llmIndexReturned() {
            // Use canonical paths so the post-LLM path reconciliation pass is a no-op and the assertion
            // remains a verbatim equality check on the LLM result (the intent of this test: LLM result, not
            // the fallback). Reconciliation behaviour itself is covered by the dedicated reconcileIndexPaths
            // tests in LlmWikiPageGeneratorTest.
            final String llmIndex = "# Wiki Index\n\nTotal pages: 2\n\n- [Kubernetes](/pages/k8s.md) — K8s guide\n";
            llmClient.addResponse(llmIndex);

            final List<WikiPageGenerator.PageInfo> pages = List.of(
                    new WikiPageGenerator.PageInfo("/pages/k8s.md", "Kubernetes", "Container orchestration", List.of()),
                    new WikiPageGenerator.PageInfo("/pages/docker.md", "Docker", "Container runtime", List.of()));

            final String result = generator.generateIndexContent(SCOPE, "agent/ctx/wiki", pages);

            assertThat(result).isEqualTo(llmIndex);
        }

        @Test
        @DisplayName("Fallback index when LLM fails")
        void fallbackOnLlmFailure() {
            llmClient.setShouldFail(true);

            final List<WikiPageGenerator.PageInfo> pages = List
                    .of(new WikiPageGenerator.PageInfo("/pages/k8s.md", "Kubernetes", null, List.of()));

            final String result = generator.generateIndexContent(SCOPE, "agent/ctx/wiki", pages);

            assertThat(result).contains("# Wiki Index");
            assertThat(result).contains("Kubernetes");
            assertThat(result).contains("/pages/k8s.md");
            assertThat(result).contains("Total pages: 1");
        }

        @Test
        @DisplayName("Fallback index when LLM returns blank")
        void fallbackOnBlankResponse() {
            llmClient.addResponse("  \n  ");

            final List<WikiPageGenerator.PageInfo> pages = List
                    .of(new WikiPageGenerator.PageInfo("/pages/doc.md", "Document", "Preview", List.of()));

            final String result = generator.generateIndexContent(SCOPE, "agent/ctx/wiki", pages);

            assertThat(result).contains("# Wiki Index");
            assertThat(result).contains("Document");
        }

        @Test
        @DisplayName("Index prompt is truncated when exceeding max length")
        void indexPromptTruncated() {
            final LlmWikiPageGenerator smallGen = LlmWikiPageGenerator.builder().llmClient(llmClient)
                    .maxIndexPromptLength(200).build();
            llmClient.addResponse("# Wiki Index\n\nTruncated result.");

            final List<WikiPageGenerator.PageInfo> manyPages = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                manyPages.add(new WikiPageGenerator.PageInfo("/pages/page-" + i + ".md", "Page " + i,
                        "This is a preview for page " + i, List.of()));
            }

            smallGen.generateIndexContent(SCOPE, "agent/ctx/wiki", manyPages);

            final String lastMessage = llmClient.getLastUserMessage();
            assertThat(lastMessage).contains("more pages (truncated)");
        }
    }

    // -------------------------------------------------------------------------
    // Thread-safe LLM client stub
    // -------------------------------------------------------------------------

    static class StubLlmClient implements LlmClient {

        private final List<String> responses = new ArrayList<>();
        private final AtomicInteger callIndex = new AtomicInteger(0);
        private final AtomicInteger callCount = new AtomicInteger(0);
        private volatile boolean shouldFail;
        private volatile String lastSystemPrompt;
        private volatile String lastUserMessage;
        private volatile LlmModel lastModelConfig;
        private volatile LlmCallMetadata lastMetadata;

        void addResponse(String response) {
            responses.add(response);
        }

        void setShouldFail(boolean shouldFail) {
            this.shouldFail = shouldFail;
        }

        int getCallCount() {
            return callCount.get();
        }

        String getLastSystemPrompt() {
            return lastSystemPrompt;
        }

        String getLastUserMessage() {
            return lastUserMessage;
        }

        LlmModel getLastModelConfig() {
            return lastModelConfig;
        }

        LlmCallMetadata getLastMetadata() {
            return lastMetadata;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            callCount.incrementAndGet();
            lastSystemPrompt = systemPrompt;
            lastModelConfig = modelConfig;
            lastMetadata = metadata;
            if (messages != null && !messages.isEmpty()) {
                lastUserMessage = messages.get(0).getContent();
            }
            if (shouldFail) {
                throw new at.aimon.core.llm.exception.LlmClientException("Stub LLM failure");
            }
            final int idx = callIndex.getAndIncrement();
            if (idx < responses.size()) {
                return LlmResponse.text(responses.get(idx));
            }
            return LlmResponse.text("# Fallback\n\nNo stub response configured.");
        }

        @Override
        public String getProviderName() {
            return "StubLlmClient";
        }

    }
}
