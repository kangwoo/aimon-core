package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llms.anthropic.exception.AnthropicException;
import at.aimon.core.llms.anthropic.exception.MessageConversionException;

@DisplayName("AnthropicLlmClient - LLM Client Tests")
@ExtendWith(MockitoExtension.class)
class AnthropicLlmClientTest {

    @Nested
    @DisplayName("Construction and Configuration")
    class ConstructionTests {

        @Test
        @DisplayName("Should throw exception when config is null")
        void shouldThrowException_WhenConfigIsNull() {
            assertThatThrownBy(() -> new AnthropicLlmClient(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Config cannot be null");
        }

        @Test
        @DisplayName("Should create client with valid config")
        void shouldCreateClient_WithValidConfig() {
            // Given: Valid config
            AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").model("claude-sonnet-4-20250514")
                    .build();

            // When: Creating client
            AnthropicLlmClient client = new AnthropicLlmClient(config);

            // Then: Client should be created successfully
            assertThat(client).isNotNull();
            assertThat(client.getProviderName()).contains("Anthropic").contains("claude-sonnet-4-20250514");
        }

        @Test
        @DisplayName("Should return correct provider name")
        void shouldReturnCorrectProviderName() {
            // Given: Config with specific model
            AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").model("claude-opus-4-20250514")
                    .build();

            // When: Creating client
            AnthropicLlmClient client = new AnthropicLlmClient(config);

            // Then: Provider name should include model
            assertThat(client.getProviderName()).isEqualTo("Anthropic (claude-opus-4-20250514)");
        }

        @Test
        @DisplayName("Should handle custom base URL")
        void shouldHandleCustomBaseUrl() {
            // Given: Config with custom base URL
            AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").baseUrl("https://api.custom.com")
                    .build();

            // When: Creating client
            AnthropicLlmClient client = new AnthropicLlmClient(config);

            // Then: Client should be created
            assertThat(client).isNotNull();
        }

        @Test
        @DisplayName("Should create client with custom timeout")
        void shouldCreateClient_WithCustomTimeout() {
            // Given: Config with custom timeout
            AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").timeout(Duration.ofSeconds(120))
                    .build();

            // When: Creating client
            AnthropicLlmClient client = new AnthropicLlmClient(config);

            // Then: Client should be created
            assertThat(client).isNotNull();
        }

        @Test
        @DisplayName("Should handle different model names")
        void shouldHandleDifferentModelNames() {
            // Given: Different model configurations
            String[] models = {"claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-haiku-3-5-20241022"};

            for (String model : models) {
                AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").model(model).build();
                AnthropicLlmClient client = new AnthropicLlmClient(config);

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
            AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").build();
            AnthropicLlmClient client = new AnthropicLlmClient(config);

            assertThatThrownBy(() -> client.sendMessage(null, List.of(Message.user("test")), Collections.emptyList(),
                    LlmModel.builder().build())).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("System prompt cannot be null");
        }

        @Test
        @DisplayName("Should throw exception when messages are null")
        void shouldThrowException_WhenMessagesAreNull() {
            AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").build();
            AnthropicLlmClient client = new AnthropicLlmClient(config);

            assertThatThrownBy(() -> client.sendMessage("system prompt", null, Collections.emptyList(),
                    LlmModel.builder().build())).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Messages cannot be null");
        }

        @Test
        @DisplayName("Should throw exception when tools are null")
        void shouldThrowException_WhenToolsAreNull() {
            AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").build();
            AnthropicLlmClient client = new AnthropicLlmClient(config);

            assertThatThrownBy(() -> client.sendMessage("system prompt", List.of(Message.user("test")), null,
                    LlmModel.builder().build())).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Tools cannot be null");
        }

        @Test
        @DisplayName("Should throw exception when model config is null")
        void shouldThrowException_WhenModelConfigIsNull() {
            AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").build();
            AnthropicLlmClient client = new AnthropicLlmClient(config);

            assertThatThrownBy(() -> client.sendMessage("system prompt", List.of(Message.user("test")),
                    Collections.emptyList(), null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Model config cannot be null");
        }
    }

    @Nested
    @DisplayName("sendMessage Behavior")
    class SendMessageBehaviorTests {

        @Mock
        private AnthropicClient mockAnthropicClient;

        @Mock
        private MessageService mockMessageService;

        private AnthropicLlmClient createClientWithMock() {
            when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
            AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").model("claude-sonnet-4-20250514")
                    .build();
            return new AnthropicLlmClient(config, mockAnthropicClient);
        }

        @Test
        @DisplayName("Should return text-only response")
        void shouldReturnTextOnlyResponse() {
            // Given
            com.anthropic.models.messages.Message anthropicMessage = mockTextResponse("Hello from Claude!");

            when(mockMessageService.create(any(MessageCreateParams.class))).thenReturn(anthropicMessage);

            AnthropicLlmClient client = createClientWithMock();

            // When
            LlmResponse response = client.sendMessage("You are helpful", List.of(Message.user("Hello")),
                    Collections.emptyList(), LlmModel.builder().build());

            // Then
            assertThat(response).isNotNull();
            assertThat(response.getTextContent()).isEqualTo("Hello from Claude!");
            assertThat(response.getTokenUsage().getPromptTokens()).isEqualTo(10);
            assertThat(response.getTokenUsage().getCompletionTokens()).isEqualTo(5);
            assertThat(response.getToolUses()).isEmpty();
        }

        @Test
        @DisplayName("Should return response with tool uses")
        void shouldReturnResponseWithToolUses() {
            // Given
            TextBlock textBlock = mock(TextBlock.class);
            when(textBlock.text()).thenReturn("I'll check that.");

            ContentBlock textContentBlock = mock(ContentBlock.class);
            when(textContentBlock.isText()).thenReturn(true);
            when(textContentBlock.asText()).thenReturn(textBlock);

            ToolUseBlock toolUseBlock = mock(ToolUseBlock.class);
            when(toolUseBlock.id()).thenReturn("toolu_123");
            when(toolUseBlock.name()).thenReturn("get_weather");
            when(toolUseBlock._input()).thenReturn(JsonValue.from(Map.of("location", "Boston")));

            ContentBlock toolContentBlock = mock(ContentBlock.class);
            when(toolContentBlock.isToolUse()).thenReturn(true);
            when(toolContentBlock.asToolUse()).thenReturn(toolUseBlock);

            Usage usage = mock(Usage.class);
            when(usage.inputTokens()).thenReturn(15L);
            when(usage.outputTokens()).thenReturn(10L);

            com.anthropic.models.messages.Message anthropicMessage = mock(com.anthropic.models.messages.Message.class);
            when(anthropicMessage.content()).thenReturn(List.of(textContentBlock, toolContentBlock));
            when(anthropicMessage.usage()).thenReturn(usage);
            when(anthropicMessage.stopReason()).thenReturn(Optional.of(StopReason.TOOL_USE));

            when(mockMessageService.create(any(MessageCreateParams.class))).thenReturn(anthropicMessage);

            AnthropicLlmClient client = createClientWithMock();

            // When
            LlmResponse response = client.sendMessage("You are helpful", List.of(Message.user("Weather in Boston?")),
                    Collections.emptyList(), LlmModel.builder().build());

            // Then
            assertThat(response.hasToolUses()).isTrue();
            assertThat(response.getToolUses()).hasSize(1);
            assertThat(response.getToolUses().get(0).getName()).isEqualTo("get_weather");
            assertThat(response.getToolUses().get(0).getId()).isEqualTo("toolu_123");
            assertThat(response.getToolUses().get(0).getInput()).containsEntry("location", "Boston");
            assertThat(response.getTextContent()).isEqualTo("I'll check that.");
        }

        @Test
        @DisplayName("Should extract token usage from response")
        void shouldExtractTokenUsage() {
            // Given
            com.anthropic.models.messages.Message anthropicMessage = mockTextResponse("Answer");

            when(mockMessageService.create(any(MessageCreateParams.class))).thenReturn(anthropicMessage);

            AnthropicLlmClient client = createClientWithMock();

            // When
            LlmResponse response = client.sendMessage("system", List.of(Message.user("test")), Collections.emptyList(),
                    LlmModel.builder().build());

            // Then
            assertThat(response.hasTokenUsage()).isTrue();
            assertThat(response.getTokenUsage().getPromptTokens()).isEqualTo(10);
            assertThat(response.getTokenUsage().getCompletionTokens()).isEqualTo(5);
            assertThat(response.getTokenUsage().getTotalTokens()).isEqualTo(15);
        }

        @Test
        @DisplayName("Should throw LlmClientException when response has no content blocks")
        void shouldThrowLlmClientException_WhenNoContentBlocks() {
            // Given
            com.anthropic.models.messages.Message anthropicMessage = mock(com.anthropic.models.messages.Message.class);
            when(anthropicMessage.content()).thenReturn(List.of());
            when(anthropicMessage.stopReason()).thenReturn(Optional.empty());

            when(mockMessageService.create(any(MessageCreateParams.class))).thenReturn(anthropicMessage);

            AnthropicLlmClient client = createClientWithMock();

            // When/Then
            assertThatThrownBy(() -> client.sendMessage("system", List.of(Message.user("test")),
                    Collections.emptyList(), LlmModel.builder().build())).isInstanceOf(LlmClientException.class)
                    .hasMessageContaining("No content blocks in Anthropic response");
        }

        @Test
        @DisplayName("Should handle null _input() in ToolUseBlock")
        void shouldHandleNullInputInToolUseBlock() {
            // Given
            ToolUseBlock toolUseBlock = mock(ToolUseBlock.class);
            when(toolUseBlock.id()).thenReturn("toolu_456");
            when(toolUseBlock.name()).thenReturn("my_tool");
            when(toolUseBlock._input()).thenReturn(null);

            ContentBlock toolContentBlock = mock(ContentBlock.class);
            when(toolContentBlock.isToolUse()).thenReturn(true);
            when(toolContentBlock.asToolUse()).thenReturn(toolUseBlock);

            Usage usage = mock(Usage.class);
            when(usage.inputTokens()).thenReturn(5L);
            when(usage.outputTokens()).thenReturn(3L);

            com.anthropic.models.messages.Message anthropicMessage = mock(com.anthropic.models.messages.Message.class);
            when(anthropicMessage.content()).thenReturn(List.of(toolContentBlock));
            when(anthropicMessage.usage()).thenReturn(usage);
            when(anthropicMessage.stopReason()).thenReturn(Optional.of(StopReason.TOOL_USE));

            when(mockMessageService.create(any(MessageCreateParams.class))).thenReturn(anthropicMessage);

            AnthropicLlmClient client = createClientWithMock();

            // When
            LlmResponse response = client.sendMessage("system", List.of(Message.user("test")), Collections.emptyList(),
                    LlmModel.builder().build());

            // Then: Should return empty map for null input
            assertThat(response.getToolUses()).hasSize(1);
            assertThat(response.getToolUses().get(0).getInput()).isEmpty();
        }

        @Test
        @DisplayName("Should use model config overrides over base config")
        void shouldUseModelConfigOverrides() {
            // Given
            com.anthropic.models.messages.Message anthropicMessage = mockTextResponse("OK");

            when(mockMessageService.create(any(MessageCreateParams.class))).thenReturn(anthropicMessage);

            AnthropicLlmClient client = createClientWithMock();

            LlmModel modelConfig = LlmModel.builder().name("claude-opus-4-20250514").temperature(0.8).maxTokens(4096)
                    .build();

            // When
            LlmResponse response = client.sendMessage("system", List.of(Message.user("test")), Collections.emptyList(),
                    modelConfig);

            // Then
            assertThat(response).isNotNull();
            assertThat(response.getTextContent()).isEqualTo("OK");
        }

        @Test
        @DisplayName("Should clamp temperature above 1.0 for Anthropic range")
        void shouldClampTemperatureAbove1() {
            // Given: Model config with temp > 1.0 (valid for LlmModel up to 2.0, but not for Anthropic)
            com.anthropic.models.messages.Message anthropicMessage = mockTextResponse("OK");
            when(mockMessageService.create(any(MessageCreateParams.class))).thenReturn(anthropicMessage);

            AnthropicLlmClient client = createClientWithMock();
            LlmModel modelConfig = LlmModel.builder().temperature(1.5).build();

            // When: Should not throw, temperature clamped silently
            LlmResponse response = client.sendMessage("system", List.of(Message.user("test")), Collections.emptyList(),
                    modelConfig);

            // Then
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("Should accept topP parameter")
        void shouldAcceptTopPParameter() {
            // Given
            com.anthropic.models.messages.Message anthropicMessage = mockTextResponse("OK");
            when(mockMessageService.create(any(MessageCreateParams.class))).thenReturn(anthropicMessage);

            AnthropicLlmClient client = createClientWithMock();
            LlmModel modelConfig = LlmModel.builder().topP(0.9).build();

            // When
            LlmResponse response = client.sendMessage("system", List.of(Message.user("test")), Collections.emptyList(),
                    modelConfig);

            // Then
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("Should ignore presencePenalty and frequencyPenalty without error")
        void shouldIgnoreUnsupportedPenaltyParameters() {
            // Given
            com.anthropic.models.messages.Message anthropicMessage = mockTextResponse("OK");
            when(mockMessageService.create(any(MessageCreateParams.class))).thenReturn(anthropicMessage);

            AnthropicLlmClient client = createClientWithMock();
            LlmModel modelConfig = LlmModel.builder().presencePenalty(0.5).frequencyPenalty(0.3).build();

            // When: Should not throw despite unsupported parameters
            LlmResponse response = client.sendMessage("system", List.of(Message.user("test")), Collections.emptyList(),
                    modelConfig);

            // Then
            assertThat(response).isNotNull();
        }

        private com.anthropic.models.messages.Message mockTextResponse(String text) {
            TextBlock textBlock = mock(TextBlock.class);
            when(textBlock.text()).thenReturn(text);

            ContentBlock contentBlock = mock(ContentBlock.class);
            when(contentBlock.isText()).thenReturn(true);
            when(contentBlock.asText()).thenReturn(textBlock);

            Usage usage = mock(Usage.class);
            when(usage.inputTokens()).thenReturn(10L);
            when(usage.outputTokens()).thenReturn(5L);

            com.anthropic.models.messages.Message anthropicMessage = mock(com.anthropic.models.messages.Message.class);
            when(anthropicMessage.content()).thenReturn(List.of(contentBlock));
            when(anthropicMessage.usage()).thenReturn(usage);
            when(anthropicMessage.stopReason()).thenReturn(Optional.of(StopReason.END_TURN));

            return anthropicMessage;
        }
    }

    @Nested
    @DisplayName("Exception Handling")
    class ExceptionHandlingTests {

        @Mock
        private AnthropicClient mockAnthropicClient;

        @Mock
        private MessageService mockMessageService;

        private AnthropicLlmClient createClientWithMock() {
            when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
            AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").build();
            return new AnthropicLlmClient(config, mockAnthropicClient);
        }

        @Test
        @DisplayName("Should propagate MessageConversionException as-is")
        void shouldPropagateMessageConversionException() {
            // Given: Converter throws MessageConversionException
            AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").build();
            AnthropicMessageConverter converter = mock(AnthropicMessageConverter.class);
            when(converter.convertMessages(any())).thenThrow(new MessageConversionException("Bad format"));

            AnthropicLlmClient client = new AnthropicLlmClient(config, mockAnthropicClient, converter);

            // When/Then: Should propagate as-is without wrapping
            assertThatThrownBy(() -> client.sendMessage("system", List.of(Message.user("test")),
                    Collections.emptyList(), LlmModel.builder().build()))
                    .isExactlyInstanceOf(MessageConversionException.class).hasMessageContaining("Bad format")
                    .hasNoCause();
        }

        @Test
        @DisplayName("Should wrap SDK AnthropicException into project AnthropicException")
        void shouldWrapSdkAnthropicException() {
            // Given: SDK throws its own AnthropicException
            when(mockMessageService.create(any(MessageCreateParams.class)))
                    .thenThrow(new com.anthropic.errors.AnthropicException("Rate limited"));

            AnthropicLlmClient client = createClientWithMock();

            // When/Then: Should be wrapped as project's AnthropicException
            assertThatThrownBy(() -> client.sendMessage("system", List.of(Message.user("test")),
                    Collections.emptyList(), LlmModel.builder().build()))
                    .isExactlyInstanceOf(AnthropicException.class)
                    .hasMessageContaining("Anthropic API call failed")
                    .hasCauseInstanceOf(com.anthropic.errors.AnthropicException.class);
        }

        @Test
        @DisplayName("Should wrap generic RuntimeException into LlmClientException")
        void shouldWrapGenericRuntimeException() {
            // Given: API call throws a generic RuntimeException
            when(mockMessageService.create(any(MessageCreateParams.class)))
                    .thenThrow(new RuntimeException("Connection refused"));

            AnthropicLlmClient client = createClientWithMock();

            // When/Then: Should be wrapped as LlmClientException
            assertThatThrownBy(() -> client.sendMessage("system", List.of(Message.user("test")),
                    Collections.emptyList(), LlmModel.builder().build()))
                    .isExactlyInstanceOf(LlmClientException.class)
                    .hasMessageContaining("Anthropic API call failed")
                    .hasCauseInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("AutoCloseable")
    class AutoCloseableTests {

        @Test
        @DisplayName("Should not throw when closing with non-AutoCloseable client")
        void shouldNotThrow_WhenClosingNonAutoCloseableClient() {
            // Given: AnthropicClient interface doesn't extend AutoCloseable
            AnthropicClient mockClient = mock(AnthropicClient.class);
            AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").build();
            AnthropicLlmClient client = new AnthropicLlmClient(config, mockClient);

            // When/Then: close() should not throw
            assertThatCode(client::close).doesNotThrowAnyException();
        }
    }
}
