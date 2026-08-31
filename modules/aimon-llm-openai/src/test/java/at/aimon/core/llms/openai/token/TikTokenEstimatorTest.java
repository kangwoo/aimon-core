package at.aimon.core.llms.openai.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

@DisplayName("TikTokenEstimator Tests")
class TikTokenEstimatorTest {

    @Nested
    @DisplayName("Encoding resolution")
    class EncodingResolution {

        @Test
        @DisplayName("Null model resolves to default O200K_BASE encoding")
        void nullModelResolvesToDefault() {
            TikTokenEstimator estimator = new TikTokenEstimator();

            assertThat(estimator.getModelName()).isNull();
            assertSameTextCount(estimator, "Hello world", EncodingType.O200K_BASE);
        }

        @Test
        @DisplayName("Blank model resolves to default O200K_BASE encoding")
        void blankModelResolvesToDefault() {
            TikTokenEstimator estimator = new TikTokenEstimator("   ");

            assertSameTextCount(estimator, "Hello world", EncodingType.O200K_BASE);
        }

        @Test
        @DisplayName("Exact gpt-4o model name resolves to O200K_BASE")
        void exactGpt4oResolvesToO200kBase() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4o");

            assertSameTextCount(estimator, "Hello world", EncodingType.O200K_BASE);
        }

        @Test
        @DisplayName("Versioned gpt-4o falls back via family prefix to O200K_BASE")
        void versionedGpt4oFallsBackByFamily() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4o-2099-01-01");

            assertSameTextCount(estimator, "Hello world", EncodingType.O200K_BASE);
        }

        @Test
        @DisplayName("Exact gpt-4 model name resolves to CL100K_BASE")
        void exactGpt4ResolvesToCl100kBase() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4");

            assertSameTextCount(estimator, "Hello world", EncodingType.CL100K_BASE);
        }

        @Test
        @DisplayName("Versioned gpt-4-turbo falls back via family prefix to CL100K_BASE")
        void versionedGpt4FallsBackByFamily() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4-turbo-2099-01-01");

            assertSameTextCount(estimator, "Hello world", EncodingType.CL100K_BASE);
        }

        @Test
        @DisplayName("o1 reasoning model resolves to O200K_BASE")
        void o1ResolvesToO200kBase() {
            TikTokenEstimator estimator = new TikTokenEstimator("o1-preview");

            assertSameTextCount(estimator, "Hello world", EncodingType.O200K_BASE);
        }

        @Test
        @DisplayName("Unknown model falls back to default encoding")
        void unknownModelFallsBack() {
            TikTokenEstimator estimator = new TikTokenEstimator("totally-made-up-model");

            assertSameTextCount(estimator, "Hello world", EncodingType.O200K_BASE);
        }
    }

    @Nested
    @DisplayName("estimateText")
    class EstimateText {

        @Test
        @DisplayName("Null and empty text count zero tokens")
        void nullAndEmptyAreZero() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4o");

            assertThat(estimator.estimateText(null)).isZero();
            assertThat(estimator.estimateText("")).isZero();
        }

        @Test
        @DisplayName("Plain English text matches the underlying tiktoken encoder")
        void plainEnglishMatchesEncoder() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4o");
            Encoding reference = newReferenceEncoding(EncodingType.O200K_BASE);

            String text = "The quick brown fox jumps over the lazy dog.";
            assertThat(estimator.estimateText(text)).isEqualTo(reference.countTokens(text));
        }

        @Test
        @DisplayName("Non-ASCII text matches the underlying tiktoken encoder")
        void nonAsciiMatchesEncoder() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4o");
            Encoding reference = newReferenceEncoding(EncodingType.O200K_BASE);

            String text = "안녕하세요, 토큰 카운팅 테스트입니다.";
            assertThat(estimator.estimateText(text)).isEqualTo(reference.countTokens(text));
        }
    }

    @Nested
    @DisplayName("estimateMessage")
    class EstimateMessage {

        @Test
        @DisplayName("User text message includes per-message overhead")
        void userTextIncludesOverhead() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4o");
            Encoding reference = newReferenceEncoding(EncodingType.O200K_BASE);

            String body = "ping";
            int actual = estimator.estimateMessage(Message.user(body));

            assertThat(actual).isEqualTo(reference.countTokens(body) + 3);
        }

        @Test
        @DisplayName("Assistant message with a tool use counts the JSON-ified input")
        void assistantWithToolUseCountsJson() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4o");
            Encoding reference = newReferenceEncoding(EncodingType.O200K_BASE);

            ToolUse use = ToolUse.of("call_1", "Bash", Map.of("command", "ls -la /"));
            Message msg = Message.assistant("running shell command", List.of(use));

            int textTokens = reference.countTokens("running shell command");
            int nameTokens = reference.countTokens("Bash");
            int jsonTokens = reference.countTokens("{\"command\":\"ls -la /\"}");
            int expected = 3 + textTokens + 8 + nameTokens + jsonTokens;

            assertThat(estimator.estimateMessage(msg)).isEqualTo(expected);
        }

        @Test
        @DisplayName("Tool result message counts content with overhead")
        void toolResultCountsContentWithOverhead() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4o");
            Encoding reference = newReferenceEncoding(EncodingType.O200K_BASE);

            String body = "{\"ok\": true}";
            Message msg = Message.toolUseResults(List.of(ToolUseResult.success("call_1", body)));

            int expected = 3 + 6 + reference.countTokens(body);
            assertThat(estimator.estimateMessage(msg)).isEqualTo(expected);
        }

        @Test
        @DisplayName("Null message rejected")
        void nullMessageRejected() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4o");

            assertThatThrownBy(() -> estimator.estimateMessage(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("estimate (system + messages)")
    class EstimateRequest {

        @Test
        @DisplayName("Empty messages with no system prompt counts only the priming overhead")
        void emptyRecord() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4o");

            assertThat(estimator.estimate(null, List.of())).isEqualTo(3);
            assertThat(estimator.estimate("", List.of())).isEqualTo(3);
        }

        @Test
        @DisplayName("System prompt adds per-message overhead plus its tokens")
        void systemPromptAddsOverhead() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4o");
            Encoding reference = newReferenceEncoding(EncodingType.O200K_BASE);

            String prompt = "You are a careful assistant.";
            int expected = 3 + 3 + reference.countTokens(prompt);

            assertThat(estimator.estimate(prompt, List.of())).isEqualTo(expected);
        }

        @Test
        @DisplayName("Total of multiple messages equals sum of per-message estimates plus priming")
        void totalOfMultipleMessages() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4o");
            List<Message> messages = List.of(Message.user("hello"), Message.assistant("hi"));

            int expected = 3 + estimator.estimateMessage(messages.get(0)) + estimator.estimateMessage(messages.get(1));

            assertThat(estimator.estimate(null, messages)).isEqualTo(expected);
        }

        @Test
        @DisplayName("Null messages list rejected")
        void nullMessagesRejected() {
            TikTokenEstimator estimator = new TikTokenEstimator("gpt-4o");

            assertThatThrownBy(() -> estimator.estimate("sys", null)).isInstanceOf(NullPointerException.class);
        }
    }

    private static Encoding newReferenceEncoding(EncodingType type) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        return registry.getEncoding(type);
    }

    private static void assertSameTextCount(TikTokenEstimator estimator, String text, EncodingType expected) {
        Encoding reference = newReferenceEncoding(expected);
        assertThat(estimator.estimateText(text)).isEqualTo(reference.countTokens(text));
    }
}
