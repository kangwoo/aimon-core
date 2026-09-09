package at.aimon.core.llms.openai;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;

import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * The {@code /v1/responses} half of {@link OpenAIEndpointExchange}.
 *
 * <p>
 * Structurally the mirror of {@link OpenAIChatCompletionsExchange}: the same two SDK call forms with the same
 * {@code null}-options rule, a mapper for the stream, and a conversion for the blocking result. It catches nothing, so
 * every failure lands in the client's existing cascade.
 */
final class OpenAIResponsesExchange implements OpenAIEndpointExchange {

    private static final Logger log = LoggerFactory.getLogger(OpenAIResponsesExchange.class);

    private final OpenAIClient client;
    private final OpenAIResponsesMessageConverter converter;
    private final ResponseCreateParams params;
    private final String providerName;
    private final OpenAIDivergenceReporter reporter;

    OpenAIResponsesExchange(OpenAIClient client, OpenAIResponsesMessageConverter converter, ResponseCreateParams params,
            String providerName, OpenAIDivergenceReporter reporter) {
        this.client = Objects.requireNonNull(client, "client");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.params = Objects.requireNonNull(params, "params");
        this.providerName = Objects.requireNonNull(providerName, "providerName");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    @Override
    public LlmResponse callBlocking(RequestOptions options) {
        final Response response = options == null
                ? client.responses().create(params)
                : client.responses().create(params, options);
        return convertResponse(response);
    }

    @Override
    public OpenAIStreamHandle openStream(RequestOptions options, LlmStreamSink sink, ChunkAggregator aggregator) {
        final StreamResponse<ResponseStreamEvent> streamResponse = options == null
                ? client.responses().createStreaming(params)
                : client.responses().createStreaming(params, options);
        final OpenAIResponsesStreamingMapper mapper = new OpenAIResponsesStreamingMapper(sink, aggregator, converter,
                providerName, reporter);
        return OpenAIStreamHandle.of(streamResponse, mapper::consume);
    }

    /**
     * Converts a completed response to the neutral {@link LlmResponse}.
     *
     * <p>
     * A 200 carrying {@code status: "failed"} is a normal SDK return value, not an exception — so the failure is
     * turned into one here, from inside the client's try, where the existing cascade classifies it exactly as it
     * classifies the blocking Chat path's equivalent.
     */
    private LlmResponse convertResponse(Response response) {
        if (OpenAiResponseErrors.isFailureStatus(response)) {
            throw OpenAiResponseErrors.fromResponse(response, "OpenAI API call failed");
        }

        final OpenAIResponsesMessageConverter.Output output = converter.convertOutput(response.output(), providerName);
        if (output.reasoningWithoutEncryptedContent()) {
            reporter.report("reasoningWithoutEncryptedContent@" + providerName,
                    "This turn produced reasoning items but none carried encrypted_content, so nothing can be "
                            + "replayed on the next turn and the model will re-derive its reasoning. Check that the "
                            + "deployment honours include=reasoning.encrypted_content.");
        }

        final String status = response.status().map(value -> value._value().asString().orElse(null)).orElse(null);
        final String incompleteReason = response.incompleteDetails().flatMap(Response.IncompleteDetails::reason)
                .map(reason -> reason._value().asString().orElse(null)).orElse(null);
        final StopReason stopReason = OpenAiResponseStopReasons.fromStatus(status, incompleteReason,
                output.hasToolCalls());
        if (stopReason == StopReason.MAX_TOKENS) {
            log.warn("OpenAI response was truncated due to max_output_tokens limit");
        }

        return LlmResponse.of(output.getText(), output.getToolUses(),
                OpenAiResponseUsages.toTokenUsage(response.usage()), stopReason)
                .withReasoningTraces(output.getReasoningTraces());
    }
}
