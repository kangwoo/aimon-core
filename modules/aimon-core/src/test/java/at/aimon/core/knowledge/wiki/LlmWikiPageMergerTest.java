package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;

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

@DisplayName("LlmWikiPageMerger")
class LlmWikiPageMergerTest {

    private static final WikiScope SCOPE = new WikiScope("agent", "ctx", "wiki");

    private StubLlmClient llm;
    private LlmWikiPageMerger merger;

    @BeforeEach
    void setUp() {
        llm = new StubLlmClient();
        merger = LlmWikiPageMerger.builder().llmClient(llm).build();
    }

    private static WikiPage existingPage() {
        return WikiPage.builder().path("/wiki/pages/entity-kubernetes-pod.md").title("Kubernetes Pod")
                .content("---\ntitle: Kubernetes Pod\ntype: entity\ntags: [k8s]\nderived_from: [/raw/a.md]\n---\n\n"
                        + "# Kubernetes Pod\n\nA Pod is the smallest deployable unit. See [[containers]].")
                .type(WikiPageType.ENTITY).tags(List.of("k8s")).derivedFrom(List.of("/raw/a.md")).build();
    }

    private static GeneratedPage incomingPage() {
        return GeneratedPage.builder().type(WikiPageType.ENTITY).slug("kubernetes-pod").title("Kubernetes Pod").content(
                "---\ntitle: Kubernetes Pod\ntype: entity\ntags: [k8s, scheduling]\nderived_from: [/raw/b.md]\n---\n\n"
                        + "# Kubernetes Pod\n\nPods are scheduled by the kube-scheduler.")
                .tags(List.of("k8s", "scheduling")).derivedFrom(List.of("/raw/b.md"))
                .strategy(GeneratedPage.UpdateStrategy.MERGE).build();
    }

    @Nested
    @DisplayName("merge — happy path")
    class HappyPath {

        @Test
        @DisplayName("LLM returns valid JSON envelope, merged page is returned with REPLACE strategy")
        void llmReturnsValidJson() {
            llm.nextResponse = LlmResponse
                    .text("""
                            {
                              "title": "Kubernetes Pod",
                              "tags": ["k8s", "scheduling"],
                              "body": "# Kubernetes Pod\\n\\nA Pod is the smallest deployable unit, scheduled by kube-scheduler. See [[containers]]."
                            }
                            """);

            GeneratedPage merged = merger.merge(SCOPE, existingPage(), incomingPage());

            assertThat(merged.getStrategy()).isEqualTo(GeneratedPage.UpdateStrategy.REPLACE);
            assertThat(merged.getType()).isEqualTo(WikiPageType.ENTITY);
            assertThat(merged.getSlug()).isEqualTo("kubernetes-pod");
            assertThat(merged.getTitle()).isEqualTo("Kubernetes Pod");
            assertThat(merged.getTags()).containsExactly("k8s", "scheduling");
            assertThat(merged.getDerivedFrom()).containsExactly("/raw/a.md", "/raw/b.md");
            assertThat(merged.getContent()).contains("title: Kubernetes Pod").contains("type: entity")
                    .contains("derived_from: [/raw/a.md, /raw/b.md]").contains("kube-scheduler")
                    .contains("[[containers]]");
        }

        @Test
        @DisplayName("derivedFrom de-duplicates exact matches")
        void derivedFromDedup() {
            llm.nextResponse = LlmResponse.text("""
                    {"title": "K8s Pod", "tags": [], "body": "# K8s Pod\\n\\nMerged."}
                    """);

            GeneratedPage incoming = GeneratedPage.builder().type(WikiPageType.ENTITY).slug("kubernetes-pod")
                    .title("K8s Pod").content("---\n---\nbody").derivedFrom(List.of("/raw/a.md", "/raw/b.md"))
                    .strategy(GeneratedPage.UpdateStrategy.MERGE).build();

            GeneratedPage merged = merger.merge(SCOPE, existingPage(), incoming);

            assertThat(merged.getDerivedFrom()).containsExactly("/raw/a.md", "/raw/b.md");
        }
    }

    @Nested
    @DisplayName("merge — fallback path")
    class FallbackPath {

        @Test
        @DisplayName("LLM throws — append-only fallback preserves both bodies")
        void llmThrows() {
            llm.throwOnNextCall = new RuntimeException("transport blew up");

            GeneratedPage merged = merger.merge(SCOPE, existingPage(), incomingPage());

            assertThat(merged.getStrategy()).isEqualTo(GeneratedPage.UpdateStrategy.REPLACE);
            assertThat(merged.getDerivedFrom()).containsExactly("/raw/a.md", "/raw/b.md");
            assertThat(merged.getContent()).contains("# Kubernetes Pod") // existing body preserved
                    .contains("smallest deployable unit") // existing fact preserved
                    .contains("## Update from /raw/b.md") // append section header
                    .contains("kube-scheduler"); // incoming fact preserved
        }

        @Test
        @DisplayName("LLM returns blank — append-only fallback")
        void llmBlank() {
            llm.nextResponse = LlmResponse.text("");

            GeneratedPage merged = merger.merge(SCOPE, existingPage(), incomingPage());

            assertThat(merged.getContent()).contains("smallest deployable unit").contains("kube-scheduler")
                    .contains("## Update from /raw/b.md");
        }

        @Test
        @DisplayName("LLM returns invalid JSON — append-only fallback")
        void llmInvalidJson() {
            llm.nextResponse = LlmResponse.text("not json at all");

            GeneratedPage merged = merger.merge(SCOPE, existingPage(), incomingPage());

            assertThat(merged.getContent()).contains("smallest deployable unit").contains("kube-scheduler");
        }

        @Test
        @DisplayName("LLM returns JSON with empty body — append-only fallback")
        void llmEmptyBody() {
            llm.nextResponse = LlmResponse.text("""
                    {"title": "K8s Pod", "tags": [], "body": ""}
                    """);

            GeneratedPage merged = merger.merge(SCOPE, existingPage(), incomingPage());

            assertThat(merged.getContent()).contains("smallest deployable unit").contains("kube-scheduler");
        }
    }

    @Nested
    @DisplayName("mergeDerivedFrom helper")
    class MergeDerivedFromHelper {

        @Test
        @DisplayName("union preserves order from existing first")
        void unionOrder() {
            assertThat(LlmWikiPageMerger.mergeDerivedFrom(List.of("/a.md", "/b.md"), List.of("/c.md")))
                    .containsExactly("/a.md", "/b.md", "/c.md");
        }

        @Test
        @DisplayName("union de-duplicates exact matches")
        void unionDedup() {
            assertThat(LlmWikiPageMerger.mergeDerivedFrom(List.of("/a.md"), List.of("/a.md", "/b.md")))
                    .containsExactly("/a.md", "/b.md");
        }

        @Test
        @DisplayName("nulls become empty")
        void nullsBecomeEmpty() {
            assertThat(LlmWikiPageMerger.mergeDerivedFrom(null, List.of("/a.md"))).containsExactly("/a.md");
            assertThat(LlmWikiPageMerger.mergeDerivedFrom(List.of("/a.md"), null)).containsExactly("/a.md");
            assertThat(LlmWikiPageMerger.mergeDerivedFrom(null, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("stripFrontmatter helper")
    class StripFrontmatterHelper {

        @Test
        @DisplayName("strips a leading frontmatter block")
        void stripsBlock() {
            String content = "---\ntitle: Foo\n---\n\n# Foo\nBody.";

            assertThat(LlmWikiPageMerger.stripFrontmatter(content)).isEqualTo("# Foo\nBody.");
        }

        @Test
        @DisplayName("returns content unchanged when no frontmatter")
        void noFrontmatter() {
            assertThat(LlmWikiPageMerger.stripFrontmatter("# Foo")).isEqualTo("# Foo");
        }

        @Test
        @DisplayName("returns content unchanged on unterminated frontmatter")
        void unterminated() {
            assertThat(LlmWikiPageMerger.stripFrontmatter("---\ntitle: Foo\nNo closing"))
                    .isEqualTo("---\ntitle: Foo\nNo closing");
        }
    }

    // =========================================================================
    // Stub
    // =========================================================================

    private static final class StubLlmClient implements LlmClient {

        LlmResponse nextResponse = LlmResponse.text("");
        RuntimeException throwOnNextCall;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
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
}
