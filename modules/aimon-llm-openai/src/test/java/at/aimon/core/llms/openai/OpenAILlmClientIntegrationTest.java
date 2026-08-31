package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * Integration test for {@link OpenAILlmClient} against the real OpenAI API.
 *
 * <p>
 * Requires the environment variable {@code OPENAI_KEY} to be set with a valid API key. This prevents accidental
 * execution in CI environments without API access.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * OPENAI_KEY=sk-... ./gradlew :aimon-llm-openai:test --tests
 * "at.aimon.core.llms.openai.OpenAILlmClientIntegrationTest"
 * </pre>
 */
@DisplayName("OpenAILlmClient - Integration Tests (Real API)")
@EnabledIfEnvironmentVariable(named = "OPENAI_KEY", matches = ".+")
class OpenAILlmClientIntegrationTest {

    private static final String SYSTEM_PROMPT = "You are a helpful assistant. "
            + "Answer concisely in one or two sentences.";

    private static OpenAILlmClient client;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("OPENAI_KEY");
        OpenAIConfig config = OpenAIConfig.builder().apiKey(apiKey).model("gpt-4o-mini").maxTokens(256).build();
        client = new OpenAILlmClient(config);
    }

    @Nested
    @DisplayName("Basic Chat Completion")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class BasicChatCompletionTests {

        @Test
        @Order(1)
        @DisplayName("Should return text response for simple question")
        void shouldReturnTextResponse_ForSimpleQuestion() {
            // When
            LlmResponse response = client.sendMessage(SYSTEM_PROMPT, List.of(Message.user("What is 2 + 3?")),
                    Collections.emptyList());

            // Then
            assertThat(response).isNotNull();
            assertThat(response.hasTextContent()).isTrue();
            assertThat(response.getTextContent()).containsIgnoringCase("5");
            assertThat(response.hasToolUses()).isFalse();
        }

        @Test
        @Order(2)
        @DisplayName("Should return token usage information")
        void shouldReturnTokenUsage() {
            // When
            LlmResponse response = client.sendMessage(SYSTEM_PROMPT, List.of(Message.user("Say hello.")),
                    Collections.emptyList());

            // Then
            assertThat(response.hasTokenUsage()).isTrue();
            assertThat(response.getTokenUsage().getPromptTokens()).isGreaterThan(0);
            assertThat(response.getTokenUsage().getCompletionTokens()).isGreaterThan(0);
            assertThat(response.getTokenUsage().getTotalTokens()).isGreaterThanOrEqualTo(
                    response.getTokenUsage().getPromptTokens() + response.getTokenUsage().getCompletionTokens());
        }

        @Test
        @Order(3)
        @DisplayName("Should handle multi-turn conversation")
        void shouldHandleMultiTurnConversation() {
            // Given
            List<Message> messages = List.of(Message.user("My name is Alice."),
                    Message.assistant("Nice to meet you, Alice!"), Message.user("What is my name?"));

            // When
            LlmResponse response = client.sendMessage(SYSTEM_PROMPT, messages, Collections.emptyList());

            // Then
            assertThat(response.hasTextContent()).isTrue();
            assertThat(response.getTextContent()).containsIgnoringCase("Alice");
        }
    }

    @Nested
    @DisplayName("Tool Calling")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ToolCallingTests {

        private static final ToolDefinition GET_WEATHER_TOOL = ToolDefinition.of("get_weather",
                "Get the current weather for a location",
                Map.of("type", "object", "properties",
                        Map.of("location", Map.of("type", "string", "description", "The city name")), "required",
                        List.of("location")));

        private static final ToolDefinition CALCULATE_TOOL = ToolDefinition
                .of("calculate", "Calculate a mathematical expression",
                        Map.of("type", "object", "properties", Map.of("expression",
                                Map.of("type", "string", "description", "The math expression to evaluate"), "precision",
                                Map.of("type", "number", "description", "Number of decimal places (default: 2)")),
                                "required", List.of("expression")));

        @Test
        @Order(1)
        @DisplayName("Should invoke tool when appropriate")
        void shouldInvokeTool_WhenAppropriate() {
            // Given
            String systemPrompt = "You are a weather assistant. Use the get_weather tool to answer weather questions.";
            List<Message> messages = List.of(Message.user("What's the weather in Seoul?"));

            // When
            LlmResponse response = client.sendMessage(systemPrompt, messages, List.of(GET_WEATHER_TOOL));

            // Then
            assertThat(response.hasToolUses()).isTrue();
            assertThat(response.getToolUses()).isNotEmpty();

            ToolUse toolUse = response.getToolUses().get(0);
            assertThat(toolUse.getName()).isEqualTo("get_weather");
            assertThat(toolUse.getId()).isNotEmpty();
            assertThat(toolUse.getInput()).containsKey("location");
            assertThat(toolUse.getInput().get("location").toString()).containsIgnoringCase("Seoul");
        }

        @Test
        @Order(2)
        @DisplayName("Should complete tool use round-trip conversation")
        void shouldCompleteToolUseRoundTrip() {
            // Given: First turn - user asks weather, LLM calls tool
            String systemPrompt = "You are a weather assistant. Use the get_weather tool to answer weather questions. "
                    + "After receiving tool results, provide a natural language summary.";

            LlmResponse firstResponse = client.sendMessage(systemPrompt,
                    List.of(Message.user("What's the weather in Tokyo?")), List.of(GET_WEATHER_TOOL));

            assertThat(firstResponse.hasToolUses()).isTrue();
            ToolUse toolUse = firstResponse.getToolUses().get(0);

            // When: Second turn - provide tool result, LLM summarizes
            List<Message> messages = List.of(Message.user("What's the weather in Tokyo?"),
                    Message.assistant(firstResponse.getTextContent(), firstResponse.getToolUses()),
                    Message.toolUseResults(List.of(ToolUseResult.success(toolUse.getId(),
                            "{\"temperature\": 22, \"condition\": \"Sunny\", \"humidity\": 45}"))));

            LlmResponse secondResponse = client.sendMessage(systemPrompt, messages, List.of(GET_WEATHER_TOOL));

            // Then: LLM should respond with natural language summary
            assertThat(secondResponse.hasTextContent()).isTrue();
            assertThat(secondResponse.getTextContent().toLowerCase()).containsAnyOf("sunny", "22", "tokyo", "weather");
        }

        @Test
        @Order(3)
        @DisplayName("Should select correct tool from multiple tools")
        void shouldSelectCorrectTool_FromMultipleTools() {
            // Given
            String systemPrompt = "You are an assistant with weather and calculator tools. "
                    + "Use the appropriate tool for the user's request.";
            List<Message> messages = List.of(Message.user("What is 123 * 456?"));

            // When
            LlmResponse response = client.sendMessage(systemPrompt, messages,
                    List.of(GET_WEATHER_TOOL, CALCULATE_TOOL));

            // Then
            assertThat(response.hasToolUses()).isTrue();
            ToolUse toolUse = response.getToolUses().get(0);
            assertThat(toolUse.getName()).isEqualTo("calculate");
            assertThat(toolUse.getInput()).containsKey("expression");
        }

        @Test
        @Order(4)
        @DisplayName("Should handle tool error result gracefully")
        void shouldHandleToolErrorResult() {
            // Given: First turn - LLM calls tool
            String systemPrompt = "You are a weather assistant. Use the get_weather tool for weather questions. "
                    + "If a tool returns an error, inform the user about the issue.";

            LlmResponse firstResponse = client.sendMessage(systemPrompt,
                    List.of(Message.user("What's the weather in Atlantis?")), List.of(GET_WEATHER_TOOL));

            assertThat(firstResponse.hasToolUses()).isTrue();
            ToolUse toolUse = firstResponse.getToolUses().get(0);

            // When: Second turn - provide error tool result
            List<Message> messages = List.of(Message.user("What's the weather in Atlantis?"),
                    Message.assistant(firstResponse.getTextContent(), firstResponse.getToolUses()),
                    Message.toolUseResults(
                            List.of(ToolUseResult.error(toolUse.getId(), "Location not found: Atlantis"))));

            LlmResponse secondResponse = client.sendMessage(systemPrompt, messages, List.of(GET_WEATHER_TOOL));

            // Then: LLM should handle the error gracefully
            assertThat(secondResponse.hasTextContent()).isTrue();
            assertThat(secondResponse.getTextContent()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Model Configuration")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ModelConfigurationTests {

        @Test
        @Order(1)
        @DisplayName("Should use model config overrides")
        void shouldUseModelConfigOverrides() {
            // Given
            LlmModel modelConfig = LlmModel.builder().temperature(0.0).maxTokens(50).build();

            // When
            LlmResponse response = client.sendMessage(SYSTEM_PROMPT, List.of(Message.user("Say 'OK'.")),
                    Collections.emptyList(), modelConfig);

            // Then
            assertThat(response).isNotNull();
            assertThat(response.hasTextContent()).isTrue();
        }

        @Test
        @Order(2)
        @DisplayName("Should work with explicit model name override")
        void shouldWorkWithModelNameOverride() {
            // Given
            LlmModel modelConfig = LlmModel.builder().name("gpt-4o-mini").temperature(0.0).maxTokens(50).build();

            // When
            LlmResponse response = client.sendMessage(SYSTEM_PROMPT, List.of(Message.user("Say 'hello'.")),
                    Collections.emptyList(), modelConfig);

            // Then
            assertThat(response).isNotNull();
            assertThat(response.hasTextContent()).isTrue();
            assertThat(response.getTextContent().toLowerCase()).contains("hello");
        }

        @Test
        @Order(3)
        @DisplayName("Should return deterministic response with temperature 0")
        void shouldReturnDeterministicResponse_WithTemperatureZero() {
            // Given
            LlmModel modelConfig = LlmModel.builder().temperature(0.0).maxTokens(20).build();
            String prompt = "What is the capital of France? Answer in one word only.";

            // When: Send the same request twice
            LlmResponse response1 = client.sendMessage(SYSTEM_PROMPT, List.of(Message.user(prompt)),
                    Collections.emptyList(), modelConfig);
            LlmResponse response2 = client.sendMessage(SYSTEM_PROMPT, List.of(Message.user(prompt)),
                    Collections.emptyList(), modelConfig);

            // Then: Both responses should contain "Paris"
            assertThat(response1.getTextContent()).containsIgnoringCase("Paris");
            assertThat(response2.getTextContent()).containsIgnoringCase("Paris");
        }
    }

    @Nested
    @DisplayName("Client Status")
    class ClientStatusTests {

        @Test
        @DisplayName("Should return correct provider name")
        void shouldReturnCorrectProviderName() {
            assertThat(client.getProviderName()).isEqualTo("OpenAI (gpt-4o-mini)");
        }
    }
}
