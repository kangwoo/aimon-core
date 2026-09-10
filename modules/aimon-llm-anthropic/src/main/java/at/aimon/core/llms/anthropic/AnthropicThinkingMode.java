package at.aimon.core.llms.anthropic;

/**
 * Which thinking dialect this request speaks — named by the operator, or looked up per model with {@link #AUTO}.
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
 * <strong>Nothing in the request builder branches on a model name.</strong> It branches on
 * {@link at.aimon.core.llm.capability.ModelCapabilities#thinkingDialect()}, which the capability registry resolves
 * from the name that is about to go on the wire. That field is what an earlier round said did not exist: the axis is
 * mutually exclusive for most models, so it had no safe two-valued default, and the answer was to make two of its
 * values not dialects at all — {@link at.aimon.core.llm.capability.ThinkingDialect#UNKNOWN} is the absence of the
 * fact, so a model no row describes keeps exactly the request it had before, and
 * {@link at.aimon.core.llm.capability.ThinkingDialect#EITHER} is the measured exception to the exclusivity.
 *
 * <p>
 * What follows for the four values here:
 *
 * <ul>
 * <li>Against a model whose dialect is <em>unknown</em>, {@link #EXTENDED} and {@link #ADAPTIVE} do what they always
 * did — the operator's word is the only fact available, and it stands.
 * <li>Against a model whose dialect is <em>known</em> and contradicts the mode, the request is translated to the
 * dialect the model speaks and the substitution is reported at {@code WARN}. Honouring the mode literally would be a
 * certain 400 and omitting the thinking would discard what was asked for, so translating loudly is the only option
 * that keeps both the turn and the intent. The neutral {@link at.aimon.core.llm.ReasoningEffort} is the intent both
 * dialects are spellings of, and {@code AnthropicThinkingBudgets} already maps it either way.
 * <li>Against a model whose row says <em>either</em> shape is accepted, {@link #EXTENDED} and {@link #ADAPTIVE} are
 * honoured unchanged and unreported. Nothing is a 400 and nothing diverges, so translating would be a substitution
 * with nothing to justify it — even where the vendor prefers the other shape, which it says for itself.
 * <li>{@link #AUTO} states the intent without the wire fact and lets the table answer.
 * </ul>
 *
 * <p>
 * The full mode × dialect table, and why translating beats both refusing and omitting, is §3 of
 * {@code docs/design/llm/reasoning-model-enablement.md}.
 *
 * @see AnthropicConfig.Builder#thinkingMode(AnthropicThinkingMode)
 * @see at.aimon.core.llm.capability.ThinkingDialect
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
    ADAPTIVE,

    /**
     * Ask for thinking in whichever dialect the model speaks, and send nothing when the table cannot say.
     *
     * <p>
     * The value that lets one setting serve a deployment running more than one Claude model: the operator states an
     * intent ("think") rather than a per-model wire fact, and
     * {@link at.aimon.core.llm.capability.ModelCapabilities#thinkingDialect()} supplies the fact. A model the registry
     * does not describe resolves to {@link at.aimon.core.llm.capability.ThinkingDialect#UNKNOWN}, and this mode then
     * behaves as {@link #OFF} — with a warning, because the operator asked the table a question it could not answer,
     * and silence would look exactly like a working configuration.
     *
     * <p>
     * Registering the deployment's real name in the capability registry is the whole remedy for that, the same one
     * line that closes the sampling gap.
     *
     * <p>
     * A row saying {@link at.aimon.core.llm.capability.ThinkingDialect#EITHER} is the one case where this mode picks
     * rather than reads: both shapes are accepted, so the client sends the adaptive one and says nothing, because
     * {@code AUTO} asked the table and the table answered. Which of the two it prefers is a client policy with the
     * vendor's own deprecation notice behind it, and it lives in {@code AnthropicLlmClient} rather than on the enum —
     * a row states a fact, and no probe measured a preference.
     *
     * <p>
     * One thing this mode cannot carry: an explicit {@link AnthropicConfig#getThinkingBudgetTokens()}, which
     * {@code AnthropicConfig} accepts only under {@link #EXTENDED} — a number has no meaning until the dialect is
     * known, and under {@code AUTO} it is not known until the request is built. The amount of thinking comes from the
     * call's {@link at.aimon.core.llm.ReasoningEffort} instead, in whichever of the two spellings the model takes.
     *
     * <p>
     * <strong>This is not the default and the default does not move.</strong> {@link #OFF} stays, because making
     * {@code AUTO} the default would turn thinking on — and bill for it — in every deployment that upgrades without
     * reading the changelog.
     */
    AUTO
}
