package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.completions.CompletionUsage;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmClientException;

@DisplayName("OpenAILlmClient - LLM Client Tests")
@ExtendWith(MockitoExtension.class)
class OpenAILlmClientTest {

    @Nested
    @DisplayName("Construction and Configuration")
    class ConstructionTests {

        @Test
        @DisplayName("Should throw exception when config is null")
        void shouldThrowException_WhenConfigIsNull() {
            assertThatThrownBy(() -> new OpenAILlmClient(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Config cannot be null");
        }

        @Test
        @DisplayName("Should create client with valid config")
        void shouldCreateClient_WithValidConfig() {
            OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").model("gpt-4").build();

            OpenAILlmClient client = new OpenAILlmClient(config);

            assertThat(client).isNotNull();
            assertThat(client.getProviderName()).contains("OpenAI").contains("gpt-4");
        }

        @Test
        @DisplayName("Should return correct provider name")
        void shouldReturnCorrectProviderName() {
            OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").model("gpt-3.5-turbo").build();

            OpenAILlmClient client = new OpenAILlmClient(config);

            assertThat(client.getProviderName()).isEqualTo("OpenAI (gpt-3.5-turbo)");
        }

        @Test
        @DisplayName("Should handle custom base URL")
        void shouldHandleCustomBaseUrl() {
            OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").baseUrl("https://api.custom.com").build();

            OpenAILlmClient client = new OpenAILlmClient(config);

            assertThat(client).isNotNull();
        }

        @Test
        @DisplayName("Should create client with custom timeout")
        void shouldCreateClient_WithCustomTimeout() {
            OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").timeout(Duration.ofSeconds(120)).build();

            OpenAILlmClient client = new OpenAILlmClient(config);

            assertThat(client).isNotNull();
        }

        @Test
        @DisplayName("Should handle different model names")
        void shouldHandleDifferentModelNames() {
            String[] models = {"gpt-4", "gpt-4-turbo", "gpt-3.5-turbo", "gpt-4o"};

            for (String model : models) {
                OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").model(model).build();
                OpenAILlmClient client = new OpenAILlmClient(config);

                assertThat(client.getProviderName()).contains(model);
            }
        }
    }

    @Nested
    @DisplayName("Parameter Validation")
    class ParameterValidationTests {

        @Test
        @DisplayName("Should throw exception when system prompt is null")
        void shouldThrowException_WhenSystemPromptIsNull() {
            OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").build();
            OpenAILlmClient client = new OpenAILlmClient(config);

            assertThatThrownBy(() -> client.sendMessage(null, List.of(Message.user("test")), Collections.emptyList(),
                    LlmModel.builder().build())).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("System prompt cannot be null");
        }

        @Test
        @DisplayName("Should throw exception when messages are null")
        void shouldThrowException_WhenMessagesAreNull() {
            OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").build();
            OpenAILlmClient client = new OpenAILlmClient(config);

            assertThatThrownBy(() -> client.sendMessage("system prompt", null, Collections.emptyList(),
                    LlmModel.builder().build())).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Messages cannot be null");
        }

        @Test
        @DisplayName("Should throw exception when tools are null")
        void shouldThrowException_WhenToolsAreNull() {
            OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").build();
            OpenAILlmClient client = new OpenAILlmClient(config);

            assertThatThrownBy(() -> client.sendMessage("system prompt", List.of(Message.user("test")), null,
                    LlmModel.builder().build())).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Tools cannot be null");
        }

        @Test
        @DisplayName("Should throw exception when model config is null")
        void shouldThrowException_WhenModelConfigIsNull() {
            OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").build();
            OpenAILlmClient client = new OpenAILlmClient(config);

            assertThatThrownBy(() -> client.sendMessage("system prompt", List.of(Message.user("test")),
                    Collections.emptyList(), null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Model config cannot be null");
        }
    }

    @Nested
    @DisplayName("sendMessage Behavior")
    class SendMessageBehaviorTests {

        @Mock(answer = Answers.RETURNS_DEEP_STUBS)
        private OpenAIClient mockOpenAIClient;

        private OpenAILlmClient createClientWithMock() {
            OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").model("gpt-4").build();
            return new OpenAILlmClient(config, mockOpenAIClient);
        }

        @Test
        @DisplayName("Should return text-only response from OpenAI")
        void shouldReturnTextOnlyResponse() {
            // Given
            ChatCompletion completion = mockTextCompletion("Hello, world!", null);
            when(mockOpenAIClient.chat().completions().create(any(ChatCompletionCreateParams.class)))
                    .thenReturn(completion);

            OpenAILlmClient client = createClientWithMock();

            // When
            LlmResponse response = client.sendMessage("You are helpful", List.of(Message.user("Hi")),
                    Collections.emptyList(), LlmModel.builder().build());

            // Then
            assertThat(response.hasTextContent()).isTrue();
            assertThat(response.getTextContent()).isEqualTo("Hello, world!");
            assertThat(response.hasToolUses()).isFalse();
        }

        @Test
        @DisplayName("Should return response with tool calls")
        void shouldReturnResponseWithToolCalls() {
            // Given
            ChatCompletionMessageFunctionToolCall.Function function = ChatCompletionMessageFunctionToolCall.Function
                    .builder().name("get_weather").arguments("{\"location\":\"Boston\"}").build();

            ChatCompletionMessageFunctionToolCall functionToolCall = ChatCompletionMessageFunctionToolCall.builder()
                    .id("call_123").function(function).build();

            ChatCompletionMessageToolCall toolCall = ChatCompletionMessageToolCall.ofFunction(functionToolCall);

            ChatCompletionMessage message = mock(ChatCompletionMessage.class);
            when(message.content()).thenReturn(Optional.of("I'll check the weather."));
            when(message.toolCalls()).thenReturn(Optional.of(List.of(toolCall)));

            ChatCompletion.Choice choice = mock(ChatCompletion.Choice.class);
            when(choice.message()).thenReturn(message);
            when(choice.finishReason()).thenReturn(ChatCompletion.Choice.FinishReason.TOOL_CALLS);

            ChatCompletion completion = mock(ChatCompletion.class);
            when(completion.choices()).thenReturn(List.of(choice));
            when(completion.usage()).thenReturn(Optional.empty());

            when(mockOpenAIClient.chat().completions().create(any(ChatCompletionCreateParams.class)))
                    .thenReturn(completion);

            OpenAILlmClient client = createClientWithMock();

            // When
            LlmResponse response = client.sendMessage("You are helpful", List.of(Message.user("Weather in Boston?")),
                    Collections.emptyList(), LlmModel.builder().build());

            // Then
            assertThat(response.hasToolUses()).isTrue();
            assertThat(response.getToolUses()).hasSize(1);
            assertThat(response.getToolUses().get(0).getName()).isEqualTo("get_weather");
            assertThat(response.getToolUses().get(0).getId()).isEqualTo("call_123");
            assertThat(response.getToolUses().get(0).getInput()).containsEntry("location", "Boston");
        }

        @Test
        @DisplayName("Should extract token usage from response")
        void shouldExtractTokenUsage() {
            // Given
            CompletionUsage usage = mock(CompletionUsage.class);
            when(usage.promptTokens()).thenReturn(10L);
            when(usage.completionTokens()).thenReturn(20L);
            when(usage.totalTokens()).thenReturn(30L);

            ChatCompletion completion = mockTextCompletion("Answer", usage);
            when(mockOpenAIClient.chat().completions().create(any(ChatCompletionCreateParams.class)))
                    .thenReturn(completion);

            OpenAILlmClient client = createClientWithMock();

            // When
            LlmResponse response = client.sendMessage("system", List.of(Message.user("test")), Collections.emptyList(),
                    LlmModel.builder().build());

            // Then
            assertThat(response.hasTokenUsage()).isTrue();
            assertThat(response.getTokenUsage().getPromptTokens()).isEqualTo(10);
            assertThat(response.getTokenUsage().getCompletionTokens()).isEqualTo(20);
            assertThat(response.getTokenUsage().getTotalTokens()).isEqualTo(30);
        }

        @Test
        @DisplayName("Should return empty token usage when not present")
        void shouldReturnEmptyTokenUsage_WhenNotPresent() {
            // Given
            ChatCompletion completion = mockTextCompletion("Answer", null);
            when(mockOpenAIClient.chat().completions().create(any(ChatCompletionCreateParams.class)))
                    .thenReturn(completion);

            OpenAILlmClient client = createClientWithMock();

            // When
            LlmResponse response = client.sendMessage("system", List.of(Message.user("test")), Collections.emptyList(),
                    LlmModel.builder().build());

            // Then
            assertThat(response.hasTokenUsage()).isFalse();
        }

        @Test
        @DisplayName("Should throw LlmClientException when API call fails")
        void shouldThrowLlmClientException_WhenApiCallFails() {
            // Given
            when(mockOpenAIClient.chat().completions().create(any(ChatCompletionCreateParams.class)))
                    .thenThrow(new RuntimeException("Connection refused"));

            OpenAILlmClient client = createClientWithMock();

            // When/Then
            assertThatThrownBy(() -> client.sendMessage("system", List.of(Message.user("test")),
                    Collections.emptyList(), LlmModel.builder().build())).isInstanceOf(LlmClientException.class)
                    .hasMessageContaining("OpenAI API call failed").hasCauseInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("Should throw LlmClientException when response has no choices")
        void shouldThrowLlmClientException_WhenNoChoices() {
            // Given
            ChatCompletion completion = mock(ChatCompletion.class);
            when(completion.choices()).thenReturn(List.of());

            when(mockOpenAIClient.chat().completions().create(any(ChatCompletionCreateParams.class)))
                    .thenReturn(completion);

            OpenAILlmClient client = createClientWithMock();

            // When/Then
            assertThatThrownBy(() -> client.sendMessage("system", List.of(Message.user("test")),
                    Collections.emptyList(), LlmModel.builder().build())).isInstanceOf(LlmClientException.class)
                    .hasMessageContaining("No choices in OpenAI response");
        }

        @Test
        @DisplayName("Should use model config overrides over base config")
        void shouldUseModelConfigOverrides() {
            // Given
            ChatCompletion completion = mockTextCompletion("OK", null);
            when(mockOpenAIClient.chat().completions().create(any(ChatCompletionCreateParams.class)))
                    .thenReturn(completion);

            OpenAILlmClient client = createClientWithMock();

            LlmModel modelConfig = LlmModel.builder().name("gpt-3.5-turbo").temperature(0.8).maxTokens(4096).build();

            // When
            LlmResponse response = client.sendMessage("system", List.of(Message.user("test")), Collections.emptyList(),
                    modelConfig);

            // Then
            assertThat(response).isNotNull();
            assertThat(response.getTextContent()).isEqualTo("OK");
        }

        @Test
        @DisplayName("Should handle response with empty text content")
        void shouldHandleEmptyTextContent() {
            // Given
            ChatCompletionMessageFunctionToolCall.Function function = ChatCompletionMessageFunctionToolCall.Function
                    .builder().name("bash").arguments("{\"command\":\"ls\"}").build();

            ChatCompletionMessageFunctionToolCall functionToolCall = ChatCompletionMessageFunctionToolCall.builder()
                    .id("call_789").function(function).build();

            ChatCompletionMessageToolCall toolCall = ChatCompletionMessageToolCall.ofFunction(functionToolCall);

            ChatCompletionMessage message = mock(ChatCompletionMessage.class);
            when(message.content()).thenReturn(Optional.empty());
            when(message.toolCalls()).thenReturn(Optional.of(List.of(toolCall)));

            ChatCompletion.Choice choice = mock(ChatCompletion.Choice.class);
            when(choice.message()).thenReturn(message);
            when(choice.finishReason()).thenReturn(ChatCompletion.Choice.FinishReason.TOOL_CALLS);

            ChatCompletion completion = mock(ChatCompletion.class);
            when(completion.choices()).thenReturn(List.of(choice));
            when(completion.usage()).thenReturn(Optional.empty());

            when(mockOpenAIClient.chat().completions().create(any(ChatCompletionCreateParams.class)))
                    .thenReturn(completion);

            OpenAILlmClient client = createClientWithMock();

            // When
            LlmResponse response = client.sendMessage("system", List.of(Message.user("run ls")),
                    Collections.emptyList(), LlmModel.builder().build());

            // Then
            assertThat(response.hasToolUses()).isTrue();
            assertThat(response.getToolUses().get(0).getName()).isEqualTo("bash");
        }

        /**
         * Helper to create a mocked text-only ChatCompletion.
         */
        private ChatCompletion mockTextCompletion(String content, CompletionUsage usage) {
            ChatCompletionMessage message = mock(ChatCompletionMessage.class);
            when(message.content()).thenReturn(Optional.of(content));
            when(message.toolCalls()).thenReturn(Optional.empty());

            ChatCompletion.Choice choice = mock(ChatCompletion.Choice.class);
            when(choice.message()).thenReturn(message);
            when(choice.finishReason()).thenReturn(ChatCompletion.Choice.FinishReason.STOP);

            ChatCompletion completion = mock(ChatCompletion.class);
            when(completion.choices()).thenReturn(List.of(choice));
            when(completion.usage()).thenReturn(usage != null ? Optional.of(usage) : Optional.empty());

            return completion;
        }
    }
}
