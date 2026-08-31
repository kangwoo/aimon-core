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

@DisplayName("LlmWikiAnswerStrategy")
class LlmWikiAnswerStrategyTest {

    private static final WikiScope SCOPE = new WikiScope("agent", "ctx", "wiki");

    private StubLlmClient llm;
    private LlmWikiAnswerStrategy strategy;

    @BeforeEach
    void setUp() {
        llm = new StubLlmClient();
        strategy = LlmWikiAnswerStrategy.builder().llmClient(llm).build();
    }

    private static WikiPage entityPage(String slug, String title, String body) {
        return WikiPage.builder().path("/wiki/pages/entity-" + slug + ".md").title(title)
                .content("---\ntitle: " + title + "\ntype: entity\ntags: [k8s]\n---\n\n# " + title + "\n\n" + body)
                .type(WikiPageType.ENTITY).tags(List.of("k8s")).build();
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("LLM returns valid JSON envelope, Answer is built from it")
        void llmReturnsValidJson() {
            llm.nextResponse = LlmResponse
                    .text("""
                            {
                              "title": "How Pods Are Scheduled",
                              "body": "# How Pods Are Scheduled\\n\\nThe kube-scheduler picks a node based on resource fit. See [[pod]]."
                            }
                            """);

            AnswerRequest req = AnswerRequest.builder().question("How does kubernetes schedule pods?").build();

            Answer answer = strategy.answer(SCOPE, req,
                    List.of(entityPage("pod", "Pod", "A Pod is the smallest unit, scheduled by kube-scheduler.")));

            assertThat(answer.getTitle()).isEqualTo("How Pods Are Scheduled");
            assertThat(answer.getText()).contains("kube-scheduler").contains("[[pod]]");
            assertThat(answer.getSourceRefs()).containsExactly("/wiki/pages/entity-pod.md");
            assertThat(answer.getLlmCallCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("format hint")
    class FormatHint {

        @Test
        @DisplayName("format hint is included in the LLM user prompt")
        void hintFlowsToPrompt() {
            llm.nextResponse = LlmResponse.text("{\"title\": \"T\", \"body\": \"# T\\n\\nBody.\"}");

            AnswerRequest req = AnswerRequest.builder().question("How?").format("comparison table").build();
            strategy.answer(SCOPE, req, List.of(entityPage("pod", "Pod", "body")));

            assertThat(llm.lastUserPrompt).contains("ANSWER FORMAT HINT").contains("comparison table");
        }

        @Test
        @DisplayName("null format hint omits the hint section from the prompt")
        void noHintNoSection() {
            llm.nextResponse = LlmResponse.text("{\"title\": \"T\", \"body\": \"# T\\n\\nBody.\"}");

            AnswerRequest req = AnswerRequest.builder().question("How?").build();
            strategy.answer(SCOPE, req, List.of(entityPage("pod", "Pod", "body")));

            assertThat(llm.lastUserPrompt).doesNotContain("ANSWER FORMAT HINT");
        }
    }

    @Nested
    @DisplayName("empty context")
    class EmptyContext {

        @Test
        @DisplayName("no context pages → deterministic 'no information' answer without an LLM call")
        void noContextNoLlmCall() {
            AnswerRequest req = AnswerRequest.builder().question("How does kubernetes schedule pods?").build();

            Answer answer = strategy.answer(SCOPE, req, Collections.emptyList());

            assertThat(answer.getLlmCallCount()).isZero();
            assertThat(answer.getText()).contains("No matching wiki pages");
            assertThat(answer.getSourceRefs()).isEmpty();
        }
    }

    @Nested
    @DisplayName("fallback paths")
    class FallbackPaths {

        @Test
        @DisplayName("LLM throws → deterministic fallback lists supporting pages")
        void llmThrows() {
            llm.throwOnNextCall = new RuntimeException("transport blew up");

            AnswerRequest req = AnswerRequest.builder().question("How?").build();

            Answer answer = strategy.answer(SCOPE, req, List.of(entityPage("pod", "Pod", "Pods are scheduled."),
                    entityPage("service", "Service", "Services route traffic.")));

            assertThat(answer.getText()).contains("[[pod]]").contains("[[service]]").contains("Pod")
                    .contains("Service");
            assertThat(answer.getSourceRefs()).hasSize(2);
            assertThat(answer.getLlmCallCount()).isZero();
        }

        @Test
        @DisplayName("LLM returns blank content → fallback")
        void llmBlank() {
            llm.nextResponse = LlmResponse.text("");

            AnswerRequest req = AnswerRequest.builder().question("How?").build();
            Answer answer = strategy.answer(SCOPE, req, List.of(entityPage("pod", "Pod", "body")));

            assertThat(answer.getText()).contains("[[pod]]");
            assertThat(answer.getLlmCallCount()).isZero();
        }

        @Test
        @DisplayName("LLM returns malformed JSON → fallback")
        void malformedJson() {
            llm.nextResponse = LlmResponse.text("not even json");

            AnswerRequest req = AnswerRequest.builder().question("How?").build();
            Answer answer = strategy.answer(SCOPE, req, List.of(entityPage("pod", "Pod", "body")));

            assertThat(answer.getText()).contains("[[pod]]");
        }

        @Test
        @DisplayName("LLM returns JSON with empty body → fallback")
        void emptyBody() {
            llm.nextResponse = LlmResponse.text("{\"title\": \"T\", \"body\": \"\"}");

            AnswerRequest req = AnswerRequest.builder().question("How?").build();
            Answer answer = strategy.answer(SCOPE, req, List.of(entityPage("pod", "Pod", "body")));

            assertThat(answer.getText()).contains("[[pod]]");
        }
    }

    @Nested
    @DisplayName("stripFrontmatter helper")
    class StripFrontmatterHelper {

        @Test
        @DisplayName("strips a frontmatter block and following blank line")
        void stripsBlock() {
            String content = "---\ntitle: Foo\n---\n\n# Foo\nBody.";

            assertThat(LlmWikiAnswerStrategy.stripFrontmatter(content)).isEqualTo("# Foo\nBody.");
        }

        @Test
        @DisplayName("returns content unchanged when no frontmatter")
        void noFrontmatter() {
            assertThat(LlmWikiAnswerStrategy.stripFrontmatter("# Foo")).isEqualTo("# Foo");
        }
    }

    // =========================================================================
    // Stub
    // =========================================================================

    private static final class StubLlmClient implements LlmClient {

        LlmResponse nextResponse = LlmResponse.text("");
        RuntimeException throwOnNextCall;
        String lastUserPrompt;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            lastUserPrompt = messages.isEmpty() ? "" : messages.get(0).getContent();
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
