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
 * <strong>Two of the four constants are not dialects.</strong> {@link #UNKNOWN} is the absence of the fact — <em>this
 * table cannot answer, so do not act on it</em> — which is why a multi-valued axis is safe where a two-valued one was
 * not. Both real dialects are an HTTP 400 on a model that speaks <em>only</em> the other one, so neither can be the
 * value a model nobody has described falls back to; {@code UNKNOWN} makes the client leave whatever the caller
 * configured exactly as it was. {@link #EITHER} is the other non-dialect: the table <em>can</em> answer and the answer
 * is that both shapes are accepted. The two exist for different reasons and a client acts on them differently, which
 * is the whole reason there are two rather than one doing double duty.
 *
 * <p>
 * <strong>The invariant that keeps that safe:</strong> {@code UNKNOWN} and {@code EITHER} are values a <em>row</em>
 * may hold and a <em>request</em> never speaks. A client resolves them to one of the two real dialects, or to no
 * thinking parameter at all, before anything reaches the wire.
 *
 * <p>
 * There is deliberately no constant for the vendors that express the same axis as an effort rung alone:
 * {@link ModelCapabilities#supportsReasoningEffort()} already answers that question, and a second way to say it would
 * be a second thing to keep in step. {@link #EITHER} is not a counter-example to that: it is not an alternative
 * spelling of an existing flag, it is a fourth state of <em>this</em> axis.
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
     *
     * <p>
     * Not to be confused with {@link #EITHER}: this value says the table has no answer, that one says the answer is
     * "both". A mode that asks the table rather than answering it — an "auto" mode — can act on the second and can
     * only report the first.
     */
    UNKNOWN,

    /**
     * Both request shapes are accepted; the table has an answer and the answer is "either one works".
     *
     * <p>
     * A row states this when a model was measured to take both dialects. What a client does with it follows from
     * there and needs no policy for the ordinary case: a caller who <em>named</em> a shape gets the shape they named,
     * unchanged and unreported, because translating a working, explicitly requested request would be a substitution
     * with nothing to justify it. The one case that needs a choice is a mode that asked the table to decide, and that
     * choice is a client policy rather than a fact about the model — so it lives in the client, with its citation,
     * and not on this enum. Neither vendor preference nor the client's pick is expressible here, deliberately.
     *
     * <p>
     * Like {@link #UNKNOWN} this is never the dialect a request speaks; it is resolved to one of the two real ones
     * first.
     */
    EITHER,

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
