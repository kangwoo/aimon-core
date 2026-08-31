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

@DisplayName("LlmWikiLintStrategy")
class LlmWikiLintStrategyTest {

    private static final WikiScope SCOPE = new WikiScope("agent", "ctx", "wiki");

    private StubLlmClient llm;
    private LlmWikiLintStrategy strategy;

    @BeforeEach
    void setUp() {
        llm = new StubLlmClient();
        strategy = LlmWikiLintStrategy.builder().llmClient(llm).build();
    }

    private static WikiPage entityPage(String slug, String title, String body) {
        return WikiPage.builder().path("/wiki/pages/entity-" + slug + ".md").title(title)
                .content("---\ntitle: " + title + "\ntype: entity\ntags: [k8s]\n---\n\n# " + title + "\n\n" + body)
                .type(WikiPageType.ENTITY).tags(List.of("k8s")).build();
    }

    @Nested
    @DisplayName("lint — happy path")
    class HappyPath {

        @Test
        @DisplayName("LLM returns findings, they are converted to LintReport.Issue list")
        void returnsFindings() {
            llm.nextResponse = LlmResponse.text("""
                    {
                      "findings": [
                        {
                          "kind": "contradiction",
                          "severity": "warning",
                          "page_path": "/wiki/pages/entity-pod.md",
                          "message": "Pod is described as ephemeral here but persistent in entity-service.md"
                        },
                        {
                          "kind": "missing_concept",
                          "severity": "info",
                          "page_path": null,
                          "message": "Kubelet is referenced often but has no dedicated page"
                        }
                      ]
                    }
                    """);

            List<LintReport.Issue> issues = strategy.lint(SCOPE,
                    List.of(entityPage("pod", "Pod", "Pods are ephemeral.")));

            assertThat(issues).hasSize(2);
            assertThat(issues.get(0).getSeverity()).isEqualTo(LintReport.Severity.WARNING);
            assertThat(issues.get(0).getPagePath()).isEqualTo("/wiki/pages/entity-pod.md");
            assertThat(issues.get(0).getMessage()).contains("[CONTRADICTION]").contains("ephemeral");
            assertThat(issues.get(1).getSeverity()).isEqualTo(LintReport.Severity.INFO);
            assertThat(issues.get(1).getPagePath()).isNull();
            assertThat(issues.get(1).getMessage()).contains("[MISSING_CONCEPT]");
            assertThat(strategy.getLastCallCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("empty findings list returns empty issues")
        void emptyFindings() {
            llm.nextResponse = LlmResponse.text("{\"findings\": []}");

            List<LintReport.Issue> issues = strategy.lint(SCOPE, List.of(entityPage("pod", "Pod", "body")));

            assertThat(issues).isEmpty();
        }
    }

    @Nested
    @DisplayName("lint — empty / failure paths")
    class FailurePaths {

        @Test
        @DisplayName("empty page list short-circuits with no LLM call")
        void emptyPageList() {
            List<LintReport.Issue> issues = strategy.lint(SCOPE, Collections.emptyList());

            assertThat(issues).isEmpty();
            assertThat(strategy.getLastCallCount()).isZero();
        }

        @Test
        @DisplayName("LLM throws → empty result, never throws")
        void llmThrows() {
            llm.throwOnNextCall = new RuntimeException("blew up");

            List<LintReport.Issue> issues = strategy.lint(SCOPE, List.of(entityPage("pod", "Pod", "body")));

            assertThat(issues).isEmpty();
        }

        @Test
        @DisplayName("LLM returns blank content → empty result")
        void llmBlank() {
            llm.nextResponse = LlmResponse.text("");

            List<LintReport.Issue> issues = strategy.lint(SCOPE, List.of(entityPage("pod", "Pod", "body")));

            assertThat(issues).isEmpty();
        }

        @Test
        @DisplayName("LLM returns malformed JSON → empty result")
        void malformedJson() {
            llm.nextResponse = LlmResponse.text("not json");

            List<LintReport.Issue> issues = strategy.lint(SCOPE, List.of(entityPage("pod", "Pod", "body")));

            assertThat(issues).isEmpty();
        }

        @Test
        @DisplayName("findings with empty kind or message are skipped")
        void invalidFindingsSkipped() {
            llm.nextResponse = LlmResponse
                    .text("""
                            {"findings": [
                                {"kind": "", "severity": "warning", "message": "x"},
                                {"kind": "stale", "severity": "warning", "message": ""},
                                {"kind": "stale", "severity": "warning", "message": "Valid finding", "page_path": "/wiki/pages/entity-pod.md"}
                            ]}
                            """);

            List<LintReport.Issue> issues = strategy.lint(SCOPE, List.of(entityPage("pod", "Pod", "body")));

            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).getMessage()).contains("Valid finding");
        }
    }

    @Nested
    @DisplayName("parseLintResponse helper")
    class ParseHelper {

        @Test
        @DisplayName("unknown kind still produces an issue (labeled with the kind)")
        void unknownKindLabeled() {
            String json = """
                    {"findings": [
                        {"kind": "weirdness", "severity": "info", "message": "Something odd"}
                    ]}
                    """;

            List<LintReport.Issue> issues = LlmWikiLintStrategy.parseLintResponse(json);

            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).getMessage()).contains("[WEIRDNESS]");
        }

        @Test
        @DisplayName("missing page_path field uses null")
        void nullPagePath() {
            String json = """
                    {"findings": [
                        {"kind": "data_gap", "severity": "info", "message": "Nothing about X"}
                    ]}
                    """;

            List<LintReport.Issue> issues = LlmWikiLintStrategy.parseLintResponse(json);

            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).getPagePath()).isNull();
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
