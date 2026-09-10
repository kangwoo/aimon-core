package at.aimon.core.llms.openai;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.openai.models.Reasoning;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.Tool;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.capability.ModelCapabilities;

/**
 * The {@link ResponseCreateParams} counterpart to the client's Chat Completions request builder.
 *
 * <p>
 * Same sampling rule as the Chat path, for the same SDK reason: {@code temperature(Optional.empty())} and
 * {@code topP((Double) null)} both route through {@code JsonField.ofNullable} and put {@code "temperature": null} on
 * the wire, and {@code null} is not the one value these models accept, so it fails like any other. So omission is
 * "never
 * call the setter", and it is implemented through the shared {@link OpenAiRequestParameters#applySampling} so the two
 * endpoints cannot drift.
 *
 * <p>
 * Two differences from Chat are deliberate:
 *
 * <ul>
 * <li><strong>The tools clamp is gone; the ladder check is not.</strong> Omitting the effort because tools are present
 * is a Chat Completions rule — on this endpoint tools and reasoning coexist, which is the entire point of phase 2, so
 * the configured effort goes as asked and an unconfigured request gets the server's default. What does <em>not</em>
 * go away is {@link OpenAiRequestParameters#maySendEffort}: which rungs a model accepts is a fact about the model,
 * not about the endpoint, and no OpenAI model measured to date except {@code gpt-5.6-terra} has a {@code none} rung
 * on either surface. Dropping that check along with the clamp is how {@code reasoning.effort: "none"} reached this
 * endpoint as a 400.
 * <li><strong>{@code store} is {@code false} and {@code reasoning.encrypted_content} is asked for.</strong> With
 * {@code store: true} the server retains the exchange and offers {@code previous_response_id} as an alternative to
 * replaying items — a second source of truth that no {@code SessionRecord} knows about, in a system that resumes
 * sessions on other nodes, plus a data-retention change nobody asked for arriving as a rider on a bug fix. The
 * include is requested explicitly because {@code store: false} is precisely the case the SDK's own javadoc singles out
 * for it.
 * </ul>
 */
final class OpenAIResponsesRequestFactory {

    private static final String PENALTY_UNSUPPORTED_MESSAGE = "{} {} is set on this request but the OpenAI Responses "
            + "API has no such parameter; it is being omitted and the call will succeed without it.";

    private final OpenAIResponsesMessageConverter converter;
    private final OpenAIConfig config;
    private final OpenAIDivergenceReporter reporter;

    /**
     * @param converter
     *            the Responses converter (must not be null)
     * @param config
     *            the client config — per client, not per request (must not be null)
     * @param reporter
     *            where divergences are reported; the client's own method, so the WARN keeps its logger and its
     *            once-per-signature dedup set (must not be null)
     */
    OpenAIResponsesRequestFactory(OpenAIResponsesMessageConverter converter, OpenAIConfig config,
            OpenAIDivergenceReporter reporter) {
        this.converter = Objects.requireNonNull(converter, "converter");
        this.config = Objects.requireNonNull(config, "config");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    /**
     * Builds the request.
     *
     * <p>
     * {@code providerName} arrives per request rather than being held as a field, for the same reason
     * {@code modelName} does: it is read from an overridable method on a non-final client, and the response side
     * reads it live. A frozen copy here would tag a subclass's traces with one name and then drop every one of them
     * as foreign on the next turn — the round trip silently doing nothing, which is the failure this whole path
     * exists to remove.
     *
     * @param systemPrompt
     *            the concatenated system prompt, which becomes {@code instructions} (must not be null)
     * @param messages
     *            the conversation (must not be null)
     * @param tools
     *            the tool definitions (must not be null)
     * @param modelConfig
     *            the per-request model config (must not be null)
     * @param capabilities
     *            the resolved capabilities (must not be null)
     * @param modelName
     *            the resolved model name (must not be null)
     * @param providerName
     *            the client's provider name, as it reports it for this request (must not be null)
     * @return the built params
     */
    ResponseCreateParams build(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig, ModelCapabilities capabilities, String modelName, String providerName) {
        final List<ResponseInputItem> input = converter.convertMessages(messages, providerName, reporter);

        final ResponseCreateParams.Builder builder = ResponseCreateParams.builder().model(modelName)
                .instructions(systemPrompt).input(ResponseCreateParams.Input.ofResponse(input))
                .maxOutputTokens((long) modelConfig.getMaxTokens().orElse(config.getMaxTokens())).store(false)
                .include(List.of(ResponseIncludable.REASONING_ENCRYPTED_CONTENT));

        OpenAiRequestParameters.applySampling(modelConfig, config, capabilities, modelName,
                new ResponsesSamplingSink(builder, modelName, reporter), reporter);
        applyReasoningEffort(builder, modelConfig, capabilities, modelName);

        if (!tools.isEmpty()) {
            final List<Tool> responsesTools = converter.convertTools(tools);
            builder.tools(responsesTools);
        }

        return builder.build();
    }

    /**
     * Sets {@code reasoning.effort}, or leaves it off. No tools clamp — see the class javadoc.
     */
    private void applyReasoningEffort(ResponseCreateParams.Builder builder, LlmModel modelConfig,
            ModelCapabilities capabilities, String modelName) {
        final Optional<ReasoningEffort> requested = OpenAiRequestParameters.requestedEffort(modelConfig, config);

        if (!capabilities.supportsReasoningEffort()) {
            requested.ifPresent(effort -> OpenAiRequestParameters.reportUnsupportedEffort(effort, modelName, reporter));
            return;
        }

        // Model rule, shared with the Chat path: a rung this model's ladder does not have is omitted and reported,
        // never raised to the nearest one it does have.
        if (requested.isPresent()
                && !OpenAiRequestParameters.maySendEffort(requested.get(), capabilities, modelName, reporter)) {
            return;
        }

        requested.ifPresent(
                effort -> builder.reasoning(Reasoning.builder().effort(OpenAiReasoningEfforts.toWire(effort)).build()));
    }

    /**
     * Applies accepted sampling values to a {@link ResponseCreateParams.Builder}.
     *
     * <p>
     * The Responses API carries {@code temperature} and {@code top_p} and has <strong>no</strong> presence or
     * frequency penalty at all. A value nobody can send is reported rather than swallowed, with the same
     * once-per-signature machinery every other divergence uses — a request that silently succeeds with settings other
     * than the configured ones is the failure this reporting exists to remove.
     */
    private static final class ResponsesSamplingSink implements OpenAiRequestParameters.SamplingSink {

        private final ResponseCreateParams.Builder builder;
        private final String modelName;
        private final OpenAIDivergenceReporter reporter;

        private ResponsesSamplingSink(ResponseCreateParams.Builder builder, String modelName,
                OpenAIDivergenceReporter reporter) {
            this.builder = builder;
            this.modelName = modelName;
            this.reporter = reporter;
        }

        @Override
        public void temperature(double value) {
            builder.temperature(value);
        }

        @Override
        public void topP(double value) {
            builder.topP(value);
        }

        @Override
        public void presencePenalty(double value) {
            reportNoSuchParameter("presencePenalty", value);
        }

        @Override
        public void frequencyPenalty(double value) {
            reportNoSuchParameter("frequencyPenalty", value);
        }

        private void reportNoSuchParameter(String name, double value) {
            reporter.report(name + "=" + value + "@" + modelName + "#responses", PENALTY_UNSUPPORTED_MESSAGE, name,
                    value);
        }
    }
}
