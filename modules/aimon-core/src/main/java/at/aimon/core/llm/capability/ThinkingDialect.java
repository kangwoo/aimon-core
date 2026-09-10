package at.aimon.core.llm.capability;

/**
 * Which shape this model's thinking-request parameter takes.
 *
 * <p>
 * A vendor-shaped fact on a provider-neutral type, and deliberately so — {@link ModelCapabilities} is read by every
 * client through one registry and one configuration translator, so a second table for one vendor would make a gateway
 * deployment declare each model twice, in two shapes, under two config keys. The precedent is in the same class:
 * {@link ModelCapabilities#supportsToolsWithReasoning()} is endpoint-flavoured and read on one path only. Anthropic is
 * the vendor that has two mutually exclusive request shapes today; nothing here names it.
 *
 * <p>
 * <strong>{@link #UNKNOWN} is not a dialect.</strong> It is the absence of the fact — <em>this table cannot answer, so
 * do not act on it</em> — which is why a three-valued axis is safe where a two-valued one was not. Both real dialects
 * are an HTTP 400 on a model that speaks the other one, so neither can be the value a model nobody has described falls
 * back to; {@code UNKNOWN} makes the client leave whatever the caller configured exactly as it was.
 *
 * <p>
 * There is deliberately no fourth constant for the vendors that express the same axis as an effort rung alone:
 * {@link ModelCapabilities#supportsReasoningEffort()} already answers that question, and a second way to say it would
 * be a second thing to keep in step.
 *
 * @see ModelCapabilities#thinkingDialect()
 */
public enum ThinkingDialect {

    /**
     * Nothing is known. The client does not choose; whatever the caller configured stands.
     *
     * <p>
     * The fail-open value, and the one every model resolves to until a row says otherwise — including every model
     * behind a gateway rename. A request for such a model keeps the shape it had before this field existed.
     */
    UNKNOWN,

    /**
     * A manual token budget accompanies the request.
     *
     * <p>
     * Anthropic spells it {@code {"thinking": {"type": "enabled", "budget_tokens": N}}}.
     */
    BUDGETED,

    /**
     * The model manages its own budget; the request carries an effort rung instead.
     *
     * <p>
     * Anthropic spells it {@code {"thinking": {"type": "adaptive"}}} with the rung in {@code output_config.effort}.
     */
    ADAPTIVE
}
