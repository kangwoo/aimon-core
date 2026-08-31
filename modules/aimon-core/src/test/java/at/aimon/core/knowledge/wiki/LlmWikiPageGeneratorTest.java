package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

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

@DisplayName("LlmWikiPageGenerator")
class LlmWikiPageGeneratorTest {

    private static final WikiScope SCOPE = new WikiScope("agent", "ctx", "wiki");

    private StubLlmClient llm;
    private LlmWikiPageGenerator generator;

    @BeforeEach
    void setUp() {
        llm = new StubLlmClient();
        generator = LlmWikiPageGenerator.builder().llmClient(llm).build();
    }

    @Nested
    @DisplayName("parseExtractResponse")
    class ParseExtractResponse {

        @Test
        @DisplayName("parses a single summary page")
        void parsesSinglePage() {
            String json = """
                    {
                      "pages": [
                        {
                          "type": "summary",
                          "slug": "k8s-overview",
                          "title": "Kubernetes Overview",
                          "tags": ["k8s"],
                          "body": "# Kubernetes Overview\\n\\nA container orchestrator."
                        }
                      ]
                    }
                    """;

            List<GeneratedPage> pages = LlmWikiPageGenerator.parseExtractResponse(json, "/raw/k8s.md");

            assertThat(pages).hasSize(1);
            GeneratedPage page = pages.get(0);
            assertThat(page.getType()).isEqualTo(WikiPageType.SUMMARY);
            assertThat(page.getSlug()).isEqualTo("k8s-overview");
            assertThat(page.getTitle()).isEqualTo("Kubernetes Overview");
            assertThat(page.getTags()).containsExactly("k8s");
            assertThat(page.getDerivedFrom()).containsExactly("/raw/k8s.md");
            assertThat(page.getStrategy()).isEqualTo(GeneratedPage.UpdateStrategy.CREATE);
            assertThat(page.getContent()).contains("title: Kubernetes Overview").contains("type: summary")
                    .contains("derived_from: [/raw/k8s.md]").contains("# Kubernetes Overview");
        }

        @Test
        @DisplayName("parses multiple pages of mixed types")
        void parsesMultiplePages() {
            String json = """
                    {
                      "pages": [
                        {"type": "summary", "slug": "doc-summary", "title": "Doc", "tags": [], "body": "# Doc"},
                        {"type": "entity", "slug": "kubernetes-pod", "title": "Kubernetes Pod", "tags": ["k8s"], "body": "# Kubernetes Pod"},
                        {"type": "concept", "slug": "eventual-consistency", "title": "Eventual Consistency", "tags": [], "body": "# Eventual Consistency"}
                      ]
                    }
                    """;

            List<GeneratedPage> pages = LlmWikiPageGenerator.parseExtractResponse(json, "/raw/doc.md");

            assertThat(pages).hasSize(3);
            assertThat(pages).extracting(GeneratedPage::getType).containsExactly(WikiPageType.SUMMARY,
                    WikiPageType.ENTITY, WikiPageType.CONCEPT);
            assertThat(pages).extracting(GeneratedPage::getSlug).containsExactly("doc-summary", "kubernetes-pod",
                    "eventual-consistency");
        }

        @Test
        @DisplayName("survives a fenced JSON wrapper")
        void survivesFenceWrapper() {
            String json = "```json\n{\"pages\":[{\"type\":\"summary\",\"slug\":\"x\",\"title\":\"X\",\"tags\":[],\"body\":\"# X\"}]}\n```";

            List<GeneratedPage> pages = LlmWikiPageGenerator.parseExtractResponse(json, "/raw/x.md");

            assertThat(pages).hasSize(1);
            assertThat(pages.get(0).getSlug()).isEqualTo("x");
        }

        @Test
        @DisplayName("rejects pages with unsafe slugs")
        void rejectsUnsafeSlug() {
            String json = """
                    {"pages": [
                        {"type": "summary", "slug": "../etc/passwd", "title": "Bad", "tags": [], "body": "# Bad"},
                        {"type": "summary", "slug": "good-one", "title": "Good", "tags": [], "body": "# Good"}
                    ]}
                    """;

            List<GeneratedPage> pages = LlmWikiPageGenerator.parseExtractResponse(json, "/raw/x.md");

            assertThat(pages).hasSize(1);
            assertThat(pages.get(0).getSlug()).isEqualTo("good-one");
        }

        @Test
        @DisplayName("rejects pages with empty body")
        void rejectsEmptyBody() {
            String json = """
                    {"pages": [
                        {"type": "summary", "slug": "ok", "title": "Title", "tags": [], "body": ""}
                    ]}
                    """;

            List<GeneratedPage> pages = LlmWikiPageGenerator.parseExtractResponse(json, "/raw/x.md");

            assertThat(pages).isEmpty();
        }

        @Test
        @DisplayName("strategy field 'merge' produces a MERGE-strategy generated page")
        void strategyMergeHonored() {
            String json = """
                    {"pages": [
                        {"type": "entity", "slug": "kubernetes-pod", "title": "Pod", "tags": [], "strategy": "merge", "body": "# Pod"}
                    ]}
                    """;

            List<GeneratedPage> pages = LlmWikiPageGenerator.parseExtractResponse(json, "/raw/x.md");

            assertThat(pages).hasSize(1);
            assertThat(pages.get(0).getStrategy()).isEqualTo(GeneratedPage.UpdateStrategy.MERGE);
        }

        @Test
        @DisplayName("missing strategy defaults to CREATE")
        void strategyMissingDefaultsToCreate() {
            String json = """
                    {"pages": [
                        {"type": "entity", "slug": "kubernetes-pod", "title": "Pod", "tags": [], "body": "# Pod"}
                    ]}
                    """;

            List<GeneratedPage> pages = LlmWikiPageGenerator.parseExtractResponse(json, "/raw/x.md");

            assertThat(pages).hasSize(1);
            assertThat(pages.get(0).getStrategy()).isEqualTo(GeneratedPage.UpdateStrategy.CREATE);
        }

        @Test
        @DisplayName("unknown strategy token defaults to CREATE")
        void strategyUnknownDefaultsToCreate() {
            String json = """
                    {"pages": [
                        {"type": "entity", "slug": "x", "title": "X", "tags": [], "strategy": "obliterate", "body": "# X"}
                    ]}
                    """;

            List<GeneratedPage> pages = LlmWikiPageGenerator.parseExtractResponse(json, "/raw/x.md");

            assertThat(pages).hasSize(1);
            assertThat(pages.get(0).getStrategy()).isEqualTo(GeneratedPage.UpdateStrategy.CREATE);
        }

        @Test
        @DisplayName("downgrades ANSWER type to SUMMARY")
        void answerDowngradedToSummary() {
            String json = """
                    {"pages": [
                        {"type": "answer", "slug": "filed", "title": "Filed", "tags": [], "body": "# Filed"}
                    ]}
                    """;

            List<GeneratedPage> pages = LlmWikiPageGenerator.parseExtractResponse(json, "/raw/x.md");

            assertThat(pages).hasSize(1);
            assertThat(pages.get(0).getType()).isEqualTo(WikiPageType.SUMMARY);
        }

        @Test
        @DisplayName("returns empty list when JSON is invalid")
        void invalidJsonReturnsEmpty() {
            assertThat(LlmWikiPageGenerator.parseExtractResponse("not json at all", "/raw/x.md")).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when 'pages' field is missing")
        void missingPagesFieldReturnsEmpty() {
            assertThat(LlmWikiPageGenerator.parseExtractResponse("{\"foo\": 1}", "/raw/x.md")).isEmpty();
        }
    }

    @Nested
    @DisplayName("generateIndexContent — system prompt contract")
    class IndexSystemPromptContract {

        @Test
        @DisplayName("system prompt instructs the LLM to copy the Path verbatim")
        void promptForcesVerbatimPath() {
            llm.nextResponse = LlmResponse.text("# Wiki Index\n\n- [X](.knowledge/wiki/pages/x.md)\n");

            generator.generateIndexContent(SCOPE, "agent/ctx/wiki", List.of(
                    new WikiPageGenerator.PageInfo(".knowledge/wiki/pages/x.md", "X", null, Collections.emptyList())));

            assertThat(llm.lastSystemPrompt).contains("byte-identical to the `Path:` value")
                    .contains("Copy the path verbatim").contains("silently dropped");
        }

        @Test
        @DisplayName("system prompt no longer suggests a misleading /absolute/page/path.md placeholder")
        void promptDoesNotSuggestAbsolutePath() {
            llm.nextResponse = LlmResponse.text("# Wiki Index\n");

            generator.generateIndexContent(SCOPE, "agent/ctx/wiki", Collections.emptyList());
            // pages.isEmpty() short-circuits before the LLM is called; force a call by passing a non-empty list.
            generator.generateIndexContent(SCOPE, "agent/ctx/wiki",
                    List.of(new WikiPageGenerator.PageInfo("a.md", "A", null, Collections.emptyList())));

            assertThat(llm.lastSystemPrompt).doesNotContain("/absolute/page/path.md");
        }
    }

    @Nested
    @DisplayName("reconcileIndexPaths")
    class ReconcileIndexPaths {

        private final WikiPageGenerator.PageInfo pageA = new WikiPageGenerator.PageInfo(
                ".knowledge/product/pages/summary-foo.md", "Foo Summary", "preview", List.of("guide"));
        private final WikiPageGenerator.PageInfo pageB = new WikiPageGenerator.PageInfo(
                ".knowledge/product/pages/entity-bar.md", "Bar Entity", null, List.of("entity"));

        @Test
        @DisplayName("verbatim path passes through unchanged")
        void verbatimPathUnchanged() {
            String llmOutput = "# Wiki Index\n\n"
                    + "- [Foo Summary](.knowledge/product/pages/summary-foo.md) — preview {guide}\n";

            String result = LlmWikiPageGenerator.reconcileIndexPaths(llmOutput, List.of(pageA), "scope");

            assertThat(result).isEqualTo(llmOutput);
        }

        @Test
        @DisplayName("leading-slash hallucination is rewritten to the canonical path")
        void leadingSlashRewritten() {
            String llmOutput = "# Wiki Index\n\n"
                    + "- [Foo Summary](/knowledge/product/pages/summary-foo.md) — preview {guide}\n";

            String result = LlmWikiPageGenerator.reconcileIndexPaths(llmOutput, List.of(pageA), "scope");

            assertThat(result).contains("](.knowledge/product/pages/summary-foo.md)")
                    .doesNotContain("](/knowledge/product/pages/summary-foo.md)").contains("— preview {guide}");
        }

        @Test
        @DisplayName("invented prefix is rewritten via basename match")
        void inventedPrefixRewrittenViaBasename() {
            String llmOutput = "- [Bar Entity](/wiki/product/01ABCD/pages/entity-bar.md) {entity}\n";

            String result = LlmWikiPageGenerator.reconcileIndexPaths(llmOutput, List.of(pageB), "scope");

            assertThat(result).contains("](.knowledge/product/pages/entity-bar.md)").contains("{entity}");
        }

        @Test
        @DisplayName("falls back to title match when basename does not match any page")
        void titleFallback() {
            String llmOutput = "- [Foo Summary](nonsense.md) — preview\n";

            String result = LlmWikiPageGenerator.reconcileIndexPaths(llmOutput, List.of(pageA), "scope");

            assertThat(result).contains("](.knowledge/product/pages/summary-foo.md)");
        }

        @Test
        @DisplayName("entries that match no page by path, basename, or title are dropped")
        void unresolvableEntryDropped() {
            String llmOutput = "# Wiki Index\n\n" + "## Guides\n"
                    + "- [Foo Summary](.knowledge/product/pages/summary-foo.md) — preview {guide}\n"
                    + "- [Phantom](pages/phantom.md) — invented by the LLM\n" + "End of index.\n";

            String result = LlmWikiPageGenerator.reconcileIndexPaths(llmOutput, List.of(pageA), "scope");

            assertThat(result).contains("Foo Summary").doesNotContain("Phantom").doesNotContain("phantom.md")
                    .contains("End of index.");
        }

        @Test
        @DisplayName("non-entry lines (heading, prose, blank) are preserved verbatim")
        void nonEntryLinesPreserved() {
            String llmOutput = "# Wiki Index\n" + "\n" + "Total page count: 1\n" + "\n" + "## Guides\n"
                    + "- [Foo Summary](/knowledge/product/pages/summary-foo.md) — preview {guide}\n" + "\n"
                    + "Generated by AIMON.\n";

            String result = LlmWikiPageGenerator.reconcileIndexPaths(llmOutput, List.of(pageA), "scope");

            assertThat(result).startsWith("# Wiki Index\n").contains("Total page count: 1").contains("## Guides")
                    .endsWith("Generated by AIMON.\n");
        }

        @Test
        @DisplayName("ambiguous title (two pages share the same title) is not used for fallback")
        void ambiguousTitleSkipsTitleFallback() {
            WikiPageGenerator.PageInfo dup1 = new WikiPageGenerator.PageInfo(".knowledge/product/pages/summary-a.md",
                    "Same", null, List.of());
            WikiPageGenerator.PageInfo dup2 = new WikiPageGenerator.PageInfo(".knowledge/product/pages/summary-b.md",
                    "Same", null, List.of());
            String llmOutput = "- [Same](unrelated.md)\n";

            String result = LlmWikiPageGenerator.reconcileIndexPaths(llmOutput, List.of(dup1, dup2), "scope");

            assertThat(result).doesNotContain("summary-a.md").doesNotContain("summary-b.md");
        }

        @Test
        @DisplayName("ambiguous basename does not produce a guess")
        void ambiguousBasenameSkipsBasenameFallback() {
            WikiPageGenerator.PageInfo dup1 = new WikiPageGenerator.PageInfo(".knowledge/a/pages/foo.md", "A1", null,
                    List.of());
            WikiPageGenerator.PageInfo dup2 = new WikiPageGenerator.PageInfo(".knowledge/b/pages/foo.md", "A2", null,
                    List.of());
            // Title 'A1' is unique → we DO want it to fall through to title resolution and pick the first page.
            // The basename 'foo.md' is shared, so basename matching must NOT be used.
            String llmOutput = "- [A1](/wiki/foo.md)\n";

            String result = LlmWikiPageGenerator.reconcileIndexPaths(llmOutput, List.of(dup1, dup2), "scope");

            assertThat(result).contains("](.knowledge/a/pages/foo.md)");
        }

        @Test
        @DisplayName("empty page list passes the LLM output through unchanged")
        void emptyPagesPassthrough() {
            String llmOutput = "# Wiki Index\n\nNo pages yet.\n";

            String result = LlmWikiPageGenerator.reconcileIndexPaths(llmOutput, Collections.emptyList(), "scope");

            assertThat(result).isEqualTo(llmOutput);
        }

        @Test
        @DisplayName("preserves indentation and list marker style")
        void preservesIndentationAndMarker() {
            String llmOutput = "  * [Foo Summary](/wrong.md) {guide}\n";

            String result = LlmWikiPageGenerator.reconcileIndexPaths(llmOutput, List.of(pageA), "scope");

            assertThat(result).startsWith("  * [Foo Summary](.knowledge/product/pages/summary-foo.md)")
                    .contains("{guide}");
        }

        @Test
        @DisplayName("end-to-end: generateIndexContent rewrites a leading-slash entry the LLM produced")
        void endToEndRewriteThroughGenerateIndex() {
            llm.nextResponse = LlmResponse.text("# Wiki Index\n\n"
                    + "- [Foo Summary](/knowledge/product/pages/summary-foo.md) — preview {guide}\n");

            String result = generator.generateIndexContent(SCOPE, "agent/ctx/wiki", List.of(pageA));

            assertThat(result).contains("](.knowledge/product/pages/summary-foo.md)")
                    .doesNotContain("](/knowledge/product/pages/summary-foo.md)");
        }
    }

    @Nested
    @DisplayName("extractPages — end-to-end with stub LLM")
    class ExtractPagesEndToEnd {

        @Test
        @DisplayName("LLM returns valid JSON envelope, multiple pages are produced")
        void happyPath() {
            llm.nextResponse = LlmResponse.text("""
                    {"pages": [
                        {"type": "summary", "slug": "k8s", "title": "K8s", "tags": [], "body": "# K8s"},
                        {"type": "entity", "slug": "pod", "title": "Pod", "tags": ["k8s"], "body": "# Pod"}
                    ]}
                    """);

            List<GeneratedPage> pages = generator.extractPages(SCOPE, "/raw/k8s.md", "Kubernetes is a system.",
                    Collections.emptyList());

            assertThat(pages).hasSize(2);
            assertThat(pages).extracting(GeneratedPage::getType).containsExactly(WikiPageType.SUMMARY,
                    WikiPageType.ENTITY);
        }

        @Test
        @DisplayName("LLM throws — falls back to single SUMMARY via the legacy path")
        void llmThrowsTriggersLegacyFallback() {
            llm.throwOnNextCall = new RuntimeException("transport blew up");
            // The legacy path will invoke generatePageContent next; arrange a follow-up successful response.
            llm.queuedResponses.add(LlmResponse.text("---\ntitle: Doc\n---\n\n# Doc\n"));

            List<GeneratedPage> pages = generator.extractPages(SCOPE, "/raw/doc.md", "Some content.",
                    Collections.emptyList());

            assertThat(pages).hasSize(1);
            assertThat(pages.get(0).getType()).isEqualTo(WikiPageType.SUMMARY);
            assertThat(pages.get(0).getSlug()).isEqualTo("doc");
        }

        @Test
        @DisplayName("LLM returns empty content — falls back to single SUMMARY")
        void llmEmptyTriggersFallback() {
            llm.nextResponse = LlmResponse.text("");
            // generatePageContent (the legacy fallback) will be called next; deterministic fallback kicks in
            // and returns the buildFallbackPageContent body.
            llm.queuedResponses.add(LlmResponse.text(""));

            List<GeneratedPage> pages = generator.extractPages(SCOPE, "/raw/doc.md", "Some content.",
                    Collections.emptyList());

            assertThat(pages).hasSize(1);
            assertThat(pages.get(0).getType()).isEqualTo(WikiPageType.SUMMARY);
            assertThat(pages.get(0).getContent()).contains("Some content.");
        }
    }

    // =========================================================================
    // Stub
    // =========================================================================

    private static final class StubLlmClient implements LlmClient {

        LlmResponse nextResponse = LlmResponse.text("");
        RuntimeException throwOnNextCall;
        final java.util.Deque<LlmResponse> queuedResponses = new java.util.ArrayDeque<>();
        String lastSystemPrompt;
        String lastUserMessage;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            this.lastSystemPrompt = systemPrompt;
            if (messages != null && !messages.isEmpty()) {
                this.lastUserMessage = messages.get(0).getContent();
            }
            if (throwOnNextCall != null) {
                final RuntimeException e = throwOnNextCall;
                throwOnNextCall = null;
                throw e;
            }
            if (!queuedResponses.isEmpty()) {
                return queuedResponses.pollFirst();
            }
            return nextResponse;
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

    }
}
