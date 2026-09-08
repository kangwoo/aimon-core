package at.aimon.core.llms.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.core.RequestOptions;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;

import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.exception.LlmInvalidRequestException;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * The {@code /v1/chat/completions} half of {@link OpenAIEndpointExchange}.
 *
 * <p>
 * Extracted from {@link OpenAILlmClient}, not rewritten: the two SDK call forms, the streaming mapper and the response
 * conversion are the same code they were before this class existed. That is the point — the non-reasoning path is
 * unchanged, and "unchanged" is checkable by reading the diff rather than by trusting a claim.
 */
final class OpenAIChatCompletionsExchange implements OpenAIEndpointExchange {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatCompletionsExchange.class);

    private final com.openai.client.OpenAIClient client;
    private final OpenAIMessageConverter converter;
    private final ChatCompletionCreateParams params;

    OpenAIChatCompletionsExchange(com.openai.client.OpenAIClient client, OpenAIMessageConverter converter,
            ChatCompletionCreateParams params) {
        this.client = Objects.requireNonNull(client, "client");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.params = Objects.requireNonNull(params, "params");
    }

    @Override
    public LlmResponse callBlocking(RequestOptions options) {
        // When the caller set a per-request timeout, pass it through as a RequestOptions override; otherwise keep the
        // single-argument overload so the client-wide default timeout applies unchanged.
        final ChatCompletion result = options == null
                ? client.chat().completions().create(params)
                : client.chat().completions().create(params, options);
        return convertResponse(result);
    }

    @Override
    public OpenAIStreamHandle openStream(RequestOptions options, LlmStreamSink sink, ChunkAggregator aggregator) {
        final StreamResponse<ChatCompletionChunk> streamResponse = options == null
                ? client.chat().completions().createStreaming(params)
                : client.chat().completions().createStreaming(params, options);
        final OpenAIStreamingMapper mapper = new OpenAIStreamingMapper(sink, aggregator);
        return OpenAIStreamHandle.of(streamResponse, mapper::consume);
    }

    /**
     * Converts an OpenAI chat completion to the neutral {@link LlmResponse}.
     */
    private LlmResponse convertResponse(ChatCompletion result) {
        if (result.choices() == null || result.choices().isEmpty()) {
            throw new LlmInvalidRequestException("No choices in OpenAI response");
        }

        final var choice = result.choices().get(0);
        final var message = choice.message();

        // Log warning if response was truncated, and map the finish reason to the provider-neutral enum so
        // aimon-core can detect truncation (length) without knowing OpenAI's raw vocabulary.
        final var finishReason = choice.finishReason();
        final String finishReasonWire = finishReason == null ? null : finishReason.toString();
        if ("length".equals(finishReasonWire)) {
            log.warn("OpenAI response was truncated due to max_tokens limit");
        }
        final StopReason neutralStopReason = OpenAiStopReasons.fromWire(finishReasonWire);

        final String textContent = message.content().orElse("");
        final List<ToolUse> toolUses = new ArrayList<>();

        // Convert tool calls to tool uses
        if (message.toolCalls().isPresent() && !message.toolCalls().get().isEmpty()) {
            for (ChatCompletionMessageToolCall toolCall : message.toolCalls().get()) {
                if (toolCall.function().isPresent()) {
                    final ChatCompletionMessageFunctionToolCall functionCall = toolCall.function().get();
                    final Map<String, Object> input = converter.parseJsonToMap(functionCall.function().arguments());

                    toolUses.add(ToolUse.of(functionCall.id(), functionCall.function().name(), input));
                }
            }
        }

        return LlmResponse.of(textContent, toolUses, extractTokenUsage(result), neutralStopReason);
    }

    /**
     * Extracts token usage from an OpenAI chat completion.
     *
     * <p>
     * Chat Completions reports no reasoning-token counter, so the three-argument factory is used and
     * {@link TokenUsage#getReasoningTokens()} stays at zero on this endpoint.
     */
    private static TokenUsage extractTokenUsage(ChatCompletion result) {
        if (result.usage().isEmpty()) {
            return TokenUsage.empty();
        }

        final var usage = result.usage().get();
        return TokenUsage.of(Math.toIntExact(usage.promptTokens()), Math.toIntExact(usage.completionTokens()),
                Math.toIntExact(usage.totalTokens()));
    }
}
