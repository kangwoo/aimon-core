package at.aimon.core.llm;

/**
 * Whether a call asks the provider for a readable summary of the model's reasoning, and how detailed a one.
 *
 * <p>
 * This is what an agent definition's {@code model.reasoningSummary} is read onto, and what {@link LlmModel} carries to
 * the provider. Unset on the model means "the agent says nothing": the provider then follows its own deployment
 * setting, and its own default when that is unset too.
 *
 * <p>
 * The three levels are the ones the OpenAI deployment key ({@code llm.openai.reasoningSummary},
 * {@code aimon.llm.openai.reasoning-summary}) accepts, under the same spellings. {@link #NONE} is the one word this
 * vocabulary has and that key does not: the deployment key turns the request off by being absent, which an agent
 * cannot express once the deployment has turned it on.
 *
 * <p>
 * Today one provider honours it — OpenAI's Responses path, where it becomes {@code reasoning.summary}. A provider
 * that has no such request parameter ignores the value and reports that once; it does not fail the call. Whether a
 * model's request surface takes the parameter at all is a capability:
 * {@link at.aimon.core.llm.capability.ModelCapabilities#supportsReasoningSummary()}.
 *
 * @see LlmModel.Builder#reasoningSummary(ReasoningSummary)
 */
public enum ReasoningSummary {

    /**
     * Ask for no summary on this call, whatever the deployment is configured to ask for.
     */
    NONE,

    /** Let the server choose how much to summarise. */
    AUTO,

    /** A short summary. */
    CONCISE,

    /** A fuller summary, at a correspondingly higher output-token cost. */
    DETAILED
}
