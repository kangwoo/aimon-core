package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

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

@DisplayName("LlmSynthesisStrategy")
class LlmSynthesisStrategyTest {

    private static final WikiScope SCOPE = new WikiScope("agent", "ctx", "wiki");

    private StubLlmClient llm;
    private LlmSynthesisStrategy strategy;

    @BeforeEach
    void setUp() {
        llm = new StubLlmClient();
        strategy = LlmSynthesisStrategy.builder().llmClient(llm).build();
    }

    private static WikiPage entityPage(String slug, String title, String tag) {
        return WikiPage.builder().path("/wiki/pages/entity-" + slug + ".md").title(title)
                .content("---\ntitle: " + title + "\ntype: entity\ntags: [" + tag + "]\n---\n\n# " + title)
                .type(WikiPageType.ENTITY).tags(List.of(tag)).build();
    }

    @Nested
    @DisplayName("clusterPagesByTag helper")
    class ClusterByTag {

        @Test
        @DisplayName("groups pages by primary tag")
        void groupsByPrimaryTag() {
            List<WikiPage> pages = List.of(entityPage("pod", "Pod", "k8s"), entityPage("svc", "Service", "k8s"),
                    entityPage("table", "Table", "db"));

            Map<String, List<WikiPage>> clusters = LlmSynthesisStrategy.clusterPagesByTag(pages, 10);

            assertThat(clusters).containsOnlyKeys("k8s", "db");
            assertThat(clusters.get("k8s")).hasSize(2);
            assertThat(clusters.get("db")).hasSize(1);
        }

        @Test
        @DisplayName("drops pages without tags")
        void dropsUntaggedPages() {
            WikiPage untagged = WikiPage.builder().path("/wiki/pages/entity-x.md").title("X").content("# X")
                    .type(WikiPageType.ENTITY).build();
            List<WikiPage> pages = List.of(untagged, entityPage("pod", "Pod", "k8s"));

            Map<String, List<WikiPage>> clusters = LlmSynthesisStrategy.clusterPagesByTag(pages, 10);

            assertThat(clusters).containsOnlyKeys("k8s");
            assertThat(clusters.get("k8s")).hasSize(1);
        }

        @Test
        @DisplayName("caps at maxClusters by keeping the largest")
        void capsAtMaxClusters() {
            List<WikiPage> pages = new ArrayList<>();
            // 3 pages tagged "a", 2 tagged "b", 1 tagged "c"
            pages.add(entityPage("a1", "A1", "a"));
            pages.add(entityPage("a2", "A2", "a"));
            pages.add(entityPage("a3", "A3", "a"));
            pages.add(entityPage("b1", "B1", "b"));
            pages.add(entityPage("b2", "B2", "b"));
            pages.add(entityPage("c1", "C1", "c"));

            Map<String, List<WikiPage>> clusters = LlmSynthesisStrategy.clusterPagesByTag(pages, 2);

            assertThat(clusters).containsOnlyKeys("a", "b");
        }
    }

    @Nested
    @DisplayName("synthesize — overview pages")
    class OverviewSynthesis {

        @Test
        @DisplayName("happy path: one cluster yields one OVERVIEW page")
        void singleClusterOneOverview() {
            llm.queue(LlmResponse.text("""
                    {
                      "title": "Kubernetes Overview",
                      "slug": "kubernetes",
                      "tags": ["k8s"],
                      "body": "# Kubernetes Overview\\n\\nSee [[pod]] and [[service]]."
                    }
                    """));

            List<WikiPage> pages = List.of(entityPage("pod", "Pod", "k8s"), entityPage("service", "Service", "k8s"));

            List<GeneratedPage> result = strategy.synthesize(SCOPE, pages,
                    SynthesizeOptions.builder().types(EnumSet.of(WikiPageType.OVERVIEW)).build());

            assertThat(result).hasSize(1);
            GeneratedPage overview = result.get(0);
            assertThat(overview.getType()).isEqualTo(WikiPageType.OVERVIEW);
            assertThat(overview.getSlug()).isEqualTo("kubernetes");
            assertThat(overview.getTitle()).isEqualTo("Kubernetes Overview");
            assertThat(overview.getDerivedFrom()).containsExactly("/wiki/pages/entity-pod.md",
                    "/wiki/pages/entity-service.md");
            assertThat(overview.getContent()).contains("type: overview").contains("[[pod]]");
            assertThat(strategy.getLastCallCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("multiple clusters yield multiple OVERVIEW pages")
        void multipleClustersMultipleOverviews() {
            llm.queue(LlmResponse
                    .text("{\"title\": \"K8s\", \"slug\": \"kubernetes\", \"tags\": [], \"body\": \"# K8s\"}"));
            llm.queue(
                    LlmResponse.text("{\"title\": \"DB\", \"slug\": \"databases\", \"tags\": [], \"body\": \"# DB\"}"));

            List<WikiPage> pages = List.of(entityPage("pod", "Pod", "k8s"), entityPage("table", "Table", "db"));

            List<GeneratedPage> result = strategy.synthesize(SCOPE, pages,
                    SynthesizeOptions.builder().types(EnumSet.of(WikiPageType.OVERVIEW)).build());

            assertThat(result).hasSize(2);
            assertThat(result).extracting(GeneratedPage::getType).containsExactly(WikiPageType.OVERVIEW,
                    WikiPageType.OVERVIEW);
            assertThat(strategy.getLastCallCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("LLM failure on one cluster does not abort the rest")
        void perClusterFailureSurvived() {
            llm.queue(LlmResponse.text("not json")); // first cluster fails to parse
            llm.queue(
                    LlmResponse.text("{\"title\": \"DB\", \"slug\": \"databases\", \"tags\": [], \"body\": \"# DB\"}"));

            List<WikiPage> pages = List.of(entityPage("pod", "Pod", "k8s"), entityPage("table", "Table", "db"));

            List<GeneratedPage> result = strategy.synthesize(SCOPE, pages,
                    SynthesizeOptions.builder().types(EnumSet.of(WikiPageType.OVERVIEW)).build());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getSlug()).isEqualTo("databases");
            assertThat(strategy.getLastCallCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("strategy stops at maxLlmCalls cap")
        void stopsAtMaxLlmCalls() {
            llm.queue(LlmResponse
                    .text("{\"title\": \"K8s\", \"slug\": \"kubernetes\", \"tags\": [], \"body\": \"# K8s\"}"));
            // No second response queued — we want to verify the strategy STOPS before issuing the second call.

            List<WikiPage> pages = List.of(entityPage("pod", "Pod", "k8s"), entityPage("table", "Table", "db"));

            List<GeneratedPage> result = strategy.synthesize(SCOPE, pages,
                    SynthesizeOptions.builder().types(EnumSet.of(WikiPageType.OVERVIEW)).maxLlmCalls(1).build());

            assertThat(result).hasSize(1);
            assertThat(strategy.getLastCallCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("synthesize — synthesis page")
    class SynthesisPage {

        @Test
        @DisplayName("synthesis page is produced after overviews when SYNTHESIS is requested")
        void synthesisAfterOverviews() {
            llm.queue(LlmResponse
                    .text("{\"title\": \"K8s\", \"slug\": \"kubernetes\", \"tags\": [], \"body\": \"# K8s\"}"));
            llm.queue(LlmResponse.text(
                    "{\"title\": \"Patterns\", \"slug\": \"infra-patterns\", \"tags\": [], \"body\": \"# Patterns\"}"));

            List<WikiPage> pages = List.of(entityPage("pod", "Pod", "k8s"));

            List<GeneratedPage> result = strategy.synthesize(SCOPE, pages, SynthesizeOptions.defaults());

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getType()).isEqualTo(WikiPageType.OVERVIEW);
            assertThat(result.get(1).getType()).isEqualTo(WikiPageType.SYNTHESIS);
            assertThat(strategy.getLastCallCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("no synthesis when overviews are empty")
        void noSynthesisWithoutOverviews() {
            List<GeneratedPage> result = strategy.synthesize(SCOPE, Collections.emptyList(),
                    SynthesizeOptions.defaults());

            assertThat(result).isEmpty();
            assertThat(strategy.getLastCallCount()).isZero();
        }

        @Test
        @DisplayName("synthesis is skipped when only OVERVIEW is requested")
        void overviewOnlySkipsSynthesis() {
            llm.queue(LlmResponse
                    .text("{\"title\": \"K8s\", \"slug\": \"kubernetes\", \"tags\": [], \"body\": \"# K8s\"}"));

            List<WikiPage> pages = List.of(entityPage("pod", "Pod", "k8s"));

            List<GeneratedPage> result = strategy.synthesize(SCOPE, pages,
                    SynthesizeOptions.builder().types(EnumSet.of(WikiPageType.OVERVIEW)).build());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getType()).isEqualTo(WikiPageType.OVERVIEW);
        }
    }

    // =========================================================================
    // Stub
    // =========================================================================

    private static final class StubLlmClient implements LlmClient {

        final java.util.Deque<LlmResponse> queued = new java.util.ArrayDeque<>();

        void queue(LlmResponse response) {
            queued.add(response);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            if (queued.isEmpty()) {
                return LlmResponse.text("");
            }
            return queued.pollFirst();
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

    }
}
