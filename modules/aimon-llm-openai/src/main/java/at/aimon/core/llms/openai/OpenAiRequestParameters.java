package at.aimon.core.llms.openai;

import java.util.Optional;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.ModelCapabilities;

/**
 * Request-parameter rules that are the same on both OpenAI endpoints.
 *
 * <p>
 * Sampling suppression is one rule, not two: a model that refuses a caller-chosen {@code temperature} refuses it on
 * either endpoint, and the operator who set it deserves the same warning either way. Copying
 * the rule into a second request builder would create a second place for it to drift, and the drift would be silent —
 * a request that succeeds with settings other than the configured ones.
 *
 * <p>
 * What is <em>not</em> shared is the reasoning-effort clamp. Omitting the effort because tools are present is a Chat
 * Completions rule; on {@code /v1/responses} tools and reasoning coexist, which is the entire point
 * of routing there. Only the "this model takes no reasoning-effort parameter at all" report is shared, so that both
 * endpoints say the same sentence about the same fact.
 */
final class OpenAiRequestParameters {

    private static final String SAMPLING_UNSUPPORTED_MESSAGE = "{} {} is set on this request but {} does not accept "
            + "sampling parameters; it is being omitted and the call will succeed without it.";

    private static final String EFFORT_UNSUPPORTED_MESSAGE = "reasoningEffort {} is set on this request but {} takes "
            + "no reasoning-effort parameter; it is being omitted and the call will succeed without it.";

    private OpenAiRequestParameters() {
    }

    /**
     * Sets the four sampling parameters, or sets none of them.
     *
     * <p>
     * "Sets none of them" means the setters are never called. It cannot be expressed as passing {@code null} or an
     * empty {@link Optional}: both SDK overloads route through {@code JsonField.ofNullable}, which turns null into
     * {@code JsonNull} and puts {@code "temperature": null} on the wire — and {@code null} is not the one value these
     * models accept, so the null form fails exactly like any other non-default value. That is why the capability check
     * branches before the sink call rather than computing a nullable effective value.
     *
     * <p>
     * Measured 2026-09-09: rejection is by <em>value</em>, not by presence. {@code gpt-5-nano} and {@code o4-mini}
     * answer 200 to {@code temperature: 1.0} and 400 to {@code 0.0} (<em>"Only the default (1) value is
     * supported"</em>).
     * Suppression is still right — omitting yields that same default, so nothing is lost on the wire and every other
     * value is spared a 400 — but a caller who explicitly sets 1.0 gets a divergence warning for a call the API would
     * have accepted. Modelling "only the default is accepted" precisely was judged not worth a new capability shape.
     *
     * <p>
     * When sampling is accepted, a parameter is set if and only if somebody put a value on the request: the request's
     * {@link LlmModel} first, then the client's {@link OpenAIConfig}. There is no third step — this client does not
     * manufacture a sampling value nobody asked for.
     *
     * @param modelConfig
     *            the per-request model config (must not be null)
     * @param config
     *            the client config (must not be null)
     * @param capabilities
     *            the resolved capabilities (must not be null)
     * @param modelName
     *            the resolved model name, for the warning text (must not be null)
     * @param sink
     *            where an accepted value goes (must not be null)
     * @param reporter
     *            where a suppressed value is reported (must not be null)
     */
    static void applySampling(LlmModel modelConfig, OpenAIConfig config, ModelCapabilities capabilities,
            String modelName, SamplingSink sink, OpenAIDivergenceReporter reporter) {
        final Optional<Double> temperature = modelConfig.getTemperature().or(config::getTemperature);
        final Optional<Double> topP = modelConfig.getTopP().or(config::getTopP);
        final Optional<Double> presencePenalty = modelConfig.getPresencePenalty().or(config::getPresencePenalty);
        final Optional<Double> frequencyPenalty = modelConfig.getFrequencyPenalty().or(config::getFrequencyPenalty);

        if (!capabilities.supportsSamplingParameters()) {
            // Every suppressible value is one somebody set, so every suppression is worth reporting and an
            // unconfigured request stays silent by construction rather than by a special case.
            reportSuppressed("temperature", temperature, modelName, reporter);
            reportSuppressed("topP", topP, modelName, reporter);
            reportSuppressed("presencePenalty", presencePenalty, modelName, reporter);
            reportSuppressed("frequencyPenalty", frequencyPenalty, modelName, reporter);
            return;
        }

        temperature.ifPresent(sink::temperature);
        topP.ifPresent(sink::topP);
        presencePenalty.ifPresent(sink::presencePenalty);
        frequencyPenalty.ifPresent(sink::frequencyPenalty);
    }

    private static void reportSuppressed(String name, Optional<Double> value, String modelName,
            OpenAIDivergenceReporter reporter) {
        value.ifPresent(v -> reporter.report(name + "=" + v + "@" + modelName, SAMPLING_UNSUPPORTED_MESSAGE, name, v,
                modelName));
    }

    /**
     * Resolves the reasoning effort somebody configured for this request: the {@link LlmModel} first, then the client
     * config.
     *
     * @param modelConfig
     *            the per-request model config (must not be null)
     * @param config
     *            the client config (must not be null)
     * @return the configured effort, or empty when nobody set one
     */
    static Optional<ReasoningEffort> requestedEffort(LlmModel modelConfig, OpenAIConfig config) {
        return modelConfig.getReasoningEffort().or(config::getReasoningEffort);
    }

    /**
     * Reports one configured reasoning effort that the target model has no parameter for.
     *
     * @param effort
     *            the configured effort (must not be null)
     * @param modelName
     *            the resolved model name (must not be null)
     * @param reporter
     *            where the divergence is reported (must not be null)
     */
    static void reportUnsupportedEffort(ReasoningEffort effort, String modelName, OpenAIDivergenceReporter reporter) {
        reporter.report("reasoningEffort=" + effort + "@" + modelName, EFFORT_UNSUPPORTED_MESSAGE, effort, modelName);
    }

    /**
     * Where an accepted sampling value goes.
     *
     * <p>
     * An interface rather than four {@code Consumer<Double>}s so that an endpoint which has no slot for a parameter
     * can say so instead of silently swallowing it — {@code /v1/responses} carries {@code temperature} and
     * {@code top_p} but has no penalties at all.
     */
    interface SamplingSink {

        /**
         * @param value
         *            the configured temperature
         */
        void temperature(double value);

        /**
         * @param value
         *            the configured top_p
         */
        void topP(double value);

        /**
         * @param value
         *            the configured presence penalty
         */
        void presencePenalty(double value);

        /**
         * @param value
         *            the configured frequency penalty
         */
        void frequencyPenalty(double value);
    }
}
