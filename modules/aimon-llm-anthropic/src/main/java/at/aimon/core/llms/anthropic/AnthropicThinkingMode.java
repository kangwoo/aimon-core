package at.aimon.core.llms.anthropic;

/**
 * Which thinking dialect this deployment's model speaks, named by the operator.
 *
 * <p>
 * Anthropic has two mutually exclusive request shapes for extended thinking, availability is per-model, and sending
 * the wrong one is an HTTP 400 rather than a degraded response:
 *
 * <pre>
 * {@code {"thinking": {"type": "enabled", "budget_tokens": 10000}}}                      // EXTENDED
 * {@code {"thinking": {"type": "adaptive"}, "output_config": {"effort": "high"}}}        // ADAPTIVE
 * </pre>
 *
 * <p>
 * The split, as the vendor's per-model table has it: Claude Sonnet 4.5, Opus 4.5, Haiku 4.5 and the earlier Claude 4
 * models are <strong>extended only</strong> and reject {@code adaptive}; Opus 4.7, Opus 4.8, Opus 5, Sonnet 5 and the
 * Fable/Mythos family are <strong>adaptive only</strong> and reject {@code enabled}; the 4.6 pair accepts both, with
 * {@code enabled} deprecated. On several of the newest models thinking is <strong>on by default</strong>, which is why
 * capturing and replaying thinking blocks is not gated on this setting at all — only the request parameter is.
 *
 * <p>
 * The two server messages, verbatim, so that an operator who greps the error text lands here:
 *
 * <pre>
 * "thinking.type.enabled" is not supported for this model. Use "thinking.type.adaptive" and
 * "output_config.effort" to control thinking behavior.
 * </pre>
 *
 * <pre>
 * adaptive thinking is not supported on this model
 * </pre>
 *
 * <p>
 * <strong>Nothing in the request builder branches on a model name to pick between these.</strong> Deriving the dialect
 * from a per-model capability table would be the better answer and is deliberately a separate round — the fact needed
 * is a two-valued mutually exclusive dialect axis, which {@code at.aimon.core.llm.capability.ModelCapabilities} has no
 * field for. Until then the operator names the mode, the default is {@link #OFF}, and a mismatch fails loudly and once
 * (a 400 maps to a non-retryable {@code LlmInvalidRequestException}) rather than silently.
 *
 * @see AnthropicConfig.Builder#thinkingMode(AnthropicThinkingMode)
 */
public enum AnthropicThinkingMode {

    /**
     * Send no {@code thinking} parameter. The request body is what it was before thinking support existed, sampling
     * parameters included.
     *
     * <p>
     * This does <em>not</em> mean "the model will not think": on the always-on models it will, and those blocks are
     * still captured and replayed.
     */
    OFF,

    /**
     * {@code thinking: {"type": "enabled", "budget_tokens": N}} — the manual budget dialect.
     *
     * <p>
     * The budget comes from {@link AnthropicConfig#getThinkingBudgetTokens()} when set, otherwise from the call's
     * {@link at.aimon.core.llm.ReasoningEffort}; either way it is floored at 1024 and clamped below {@code max_tokens}.
     */
    EXTENDED,

    /**
     * {@code thinking: {"type": "adaptive"}}, with the call's {@link at.aimon.core.llm.ReasoningEffort} carried as
     * {@code output_config.effort} — the dialect the current model generation speaks.
     */
    ADAPTIVE
}
