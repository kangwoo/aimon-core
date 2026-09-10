package at.aimon.core.llms.anthropic;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.ThinkingConfigParam;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ThinkingDialect;

/**
 * Decides the whole thinking shape of one request: the {@code thinking} parameter, the {@code output_config.effort}
 * that may accompany it, and everything the operator should be told about the difference from what they configured.
 *
 * <p>
 * <strong>This class has no logger, and that absence is the design.</strong> Three of this client's warnings used to
 * describe a request other than the one sent, because they were written from inside a resolution that a later step
 * could still abandon. The remedy is not a rule to remember: the resolution cannot log, so the only way a finding
 * reaches an operator is through {@link AnthropicThinkingResolution#findingsToReport()}, which drops the ones the
 * finished request has made false. Adding a logger here would undo that, and it is one reviewable line rather than an
 * easy accident.
 *
 * <p>
 * Stateless apart from the {@link AnthropicConfig} it is constructed with, and therefore thread-safe: the client
 * holds one for its lifetime and calls it once per request.
 *
 * <p>
 * The mode × dialect table it implements is
 * {@code docs/design/llm/reasoning-model-enablement.md} §3.3, and the reporting contract is §3.1/§3.2 of
 * {@code docs/design/llm/thinking-reporting-and-dialect-records.md}.
 */
final class AnthropicThinkingResolver {

    private final AnthropicConfig config;

    AnthropicThinkingResolver(AnthropicConfig config) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
    }

    /**
     * Resolves what this request asks the model to think, and what to say about it.
     *
     * <p>
     * {@link AnthropicThinkingMode#OFF} and {@link ReasoningEffort#NONE} both mean "send nothing", uniformly across
     * both dialects. The alternative for the second — {@code thinking: {"type": "disabled"}} — is itself rejected by
     * several models, and omission cannot 400. The cost, stated rather than hidden: on a model where thinking is on
     * by default, {@code NONE} does not turn it off.
     *
     * <p>
     * <strong>Both of those are decided before the dialect is, and the order is load-bearing.</strong> Neither state
     * puts a {@code thinking} parameter on the wire, so neither can earn the 400 the dialect look-up exists to
     * prevent, and asking the table about a request that carries no thinking would produce a warning about a question
     * nobody needed answered — including under {@link AnthropicThinkingMode#AUTO}, where a caller who set
     * {@code NONE} has already said what they want.
     *
     * @param modelConfig
     *            the call's model overrides, read for its reasoning effort
     * @param maxTokens
     *            the resolved {@code max_tokens}, which is what decides whether a budget fits
     * @param capabilities
     *            the descriptor the registry resolved for {@code modelName}
     * @param modelName
     *            the name that is about to go on the wire, for the warning text and its signature
     * @return the request's thinking shape and its findings, never null
     */
    AnthropicThinkingResolution resolve(LlmModel modelConfig, int maxTokens, ModelCapabilities capabilities,
            String modelName) {
        final AnthropicThinkingResolution.Builder resolution = AnthropicThinkingResolution.builder();
        final AnthropicThinkingMode mode = config.getThinkingMode();
        final ReasoningEffort effort = requestedEffort(modelConfig).orElse(null);

        if (mode == AnthropicThinkingMode.OFF) {
            recordInertEffort(resolution, effort);
            recordInertDisplay(resolution);
            return resolution.build();
        }
        if (effort == ReasoningEffort.NONE) {
            return resolution.build();
        }

        final Optional<ThinkingDialect> dialect = resolveDialect(resolution, mode, capabilities.thinkingDialect(),
                modelName);
        if (dialect.isEmpty()) {
            return resolution.build();
        }
        if (dialect.get() == ThinkingDialect.ADAPTIVE) {
            return resolveAdaptiveThinking(resolution, effort);
        }
        recordDisplayOnBudgetedDialect(resolution);
        return resolveExtendedThinking(resolution, effort, maxTokens);
    }

    /**
     * The adaptive shape, and the rung that accompanies it.
     *
     * <p>
     * The effort is resolved <em>here</em> rather than back in the request builder, and that is what closes the third
     * of the reporting defects. {@link #intendedEffort} applies the same precedence
     * {@link #resolveExtendedThinking} does — an explicit {@code thinkingBudgetTokens} beats a rung set on the call —
     * and until this moved, only the budgeted branch said so. The path is reachable in one shape: a budget is legal
     * only under {@link AnthropicThinkingMode#EXTENDED} ({@code AnthropicConfig}), so it reaches an adaptive request
     * only by translation, and there the number cannot go on the wire at all.
     */
    private AnthropicThinkingResolution resolveAdaptiveThinking(AnthropicThinkingResolution.Builder resolution,
            ReasoningEffort effort) {
        final ThinkingConfigAdaptive.Builder adaptive = ThinkingConfigAdaptive.builder();
        // putAdditionalProperty rather than a typed setter because this SDK version does not model `display` --
        // ThinkingConfigAdaptive carries `type` and nothing else. It is the SDK's own escape hatch
        // (@JsonAnySetter/@JsonAnyGetter) and the one this repository already uses to write unmodelled request
        // fields; when the SDK grows the field, one line changes here.
        config.getThinkingDisplay()
                .ifPresent(display -> adaptive.putAdditionalProperty("display", JsonValue.from(display.wireValue())));

        // The rung comes down from resolve() rather than being read again here: one read site is what keeps the
        // gate that decided this request carries thinking and the warning that names the rung talking about the
        // same request.
        recordBudgetOverridesEffort(resolution, effort);
        AnthropicThinkingBudgets.effortFor(intendedEffort(effort)).ifPresent(resolution::outputConfigEffort);
        return resolution.parameter(ThinkingConfigParam.ofAdaptive(adaptive.build())).build();
    }

    /**
     * Which dialect this request actually speaks, or empty when it carries no {@code thinking} parameter at all.
     *
     * <p>
     * The whole of the mode × dialect table. Most of it needs no explanation — an operator's named mode against a
     * model that agrees, or against a model nothing describes, is what this client has always done, and the second of
     * those is what keeps a model outside the table byte-identical to yesterday. The cases that are not:
     *
     * <ul>
     * <li><strong>The mode contradicts a known dialect.</strong> Honouring it is a <em>certain</em> HTTP 400 — each
     * shape is rejected by a model that speaks only the other — and omitting the parameter throws away the thinking
     * that was asked for. So the request is translated to the dialect the model speaks and the substitution is
     * reported. The intent survives because both dialects are spellings of the same neutral
     * {@link ReasoningEffort}, which {@link AnthropicThinkingBudgets} maps in either direction.
     * <li><strong>{@link ThinkingDialect#EITHER}.</strong> A named mode is honoured, unreported: nothing is a 400 and
     * nothing diverges, so translating would be a substitution with nothing to justify it.
     * <li><strong>{@link AnthropicThinkingMode#AUTO} against a model nothing describes.</strong> Nothing is sent,
     * because there is no dialect to guess and a guess is a 400 half the time. Reported, unlike every other silent
     * case here, because {@code AUTO} is the one mode that asked the table a question rather than answering it.
     * </ul>
     *
     * <p>
     * This does not violate {@link ModelCapabilities#acceptedReasoningEfforts()}'s standing rule — <em>omitted and
     * reported, never raised to meet it</em>. There, omitting leaves the server's own default in force and the call
     * succeeds; here, honouring the operator literally is a failed turn, so the two situations do not compare.
     *
     * <p>
     * <strong>It returns a real dialect or nothing.</strong> {@link ThinkingDialect#UNKNOWN} and
     * {@link ThinkingDialect#EITHER} are values a row may hold and a request never speaks, and this is the one method
     * that has to keep that true.
     */
    private Optional<ThinkingDialect> resolveDialect(AnthropicThinkingResolution.Builder resolution,
            AnthropicThinkingMode mode, ThinkingDialect known, String modelName) {
        return switch (mode) {
            // OFF never reaches here -- resolve answers it before asking -- but the switch is total, and this is the
            // same answer that early return gives.
            case OFF -> Optional.empty();
            case AUTO -> resolveAutoDialect(resolution, known, modelName);
            case EXTENDED -> resolveNamedDialect(resolution, mode, ThinkingDialect.BUDGETED, known, modelName);
            case ADAPTIVE -> resolveNamedDialect(resolution, mode, ThinkingDialect.ADAPTIVE, known, modelName);
        };
    }

    private Optional<ThinkingDialect> resolveAutoDialect(AnthropicThinkingResolution.Builder resolution,
            ThinkingDialect known, String modelName) {
        if (known == ThinkingDialect.EITHER) {
            // The one place a policy is applied rather than a fact read, and it is a client decision on purpose: the
            // row says both shapes are accepted, and which of the two to prefer is not something a probe measured.
            // ADAPTIVE, on the vendor's own say-so -- its per-model table marks the budgeted shape `(deprecated)` on
            // exactly the models that carry this row, and the vendored SDK prints "'thinking.type=enabled' is
            // deprecated. Use thinking.type=adaptive instead which results in better model performance in our
            // testing" for one of them at every call. Unreported: AUTO asked the table and the table answered.
            return Optional.of(ThinkingDialect.ADAPTIVE);
        }
        if (known != ThinkingDialect.UNKNOWN) {
            return Optional.of(known);
        }
        // Failure mode 3 of the design, and the wording is the whole mitigation: an operator reading "no thinking
        // parameter" as "the model is not thinking" would be wrong on exactly the models this matters most for, since
        // several of the current generation think by default and their blocks are captured either way.
        //
        // EXPLAINS_ABSENCE, and this is the classification that most needs stating: it is the only thing said about a
        // request that carries no thinking at all, so filing it with the translations would silence it entirely.
        resolution.record(AnthropicThinkingResolution.Finding.explainsAbsence("thinkingDialectUnknown@" + modelName,
                "thinkingMode {} asks the capability registry which thinking dialect {} speaks and no row describes "
                        + "that name, so no thinking parameter is being sent and this request is the one "
                        + "thinkingMode({}) would have produced. That is a statement about the parameter, not about "
                        + "the model: one whose thinking is on by default still thinks, and its blocks are still "
                        + "captured and replayed. Register the deployment's real name in the capability registry to "
                        + "make {} answerable for it.",
                AnthropicThinkingMode.AUTO, modelName, AnthropicThinkingMode.OFF, AnthropicThinkingMode.AUTO));
        return Optional.empty();
    }

    private Optional<ThinkingDialect> resolveNamedDialect(AnthropicThinkingResolution.Builder resolution,
            AnthropicThinkingMode mode, ThinkingDialect named, ThinkingDialect known, String modelName) {
        // EITHER joins UNKNOWN here rather than getting a branch of its own, and for the opposite reason: UNKNOWN
        // means the table cannot contradict the operator, EITHER means it agrees with them. Both end in the mode
        // being honoured with nothing said, which is the same code and two different sentences.
        if (known == ThinkingDialect.UNKNOWN || known == ThinkingDialect.EITHER || known == named) {
            return Optional.of(named);
        }
        // One signature shape for both directions of the translation, carrying the model name for the same reason
        // the sampling signatures do: one client has one mode, but LlmModel can override the name, so the same mode
        // against two models is two pieces of news rather than one repeated.
        final String signature = "thinkingDialectTranslated=" + mode + "->" + known + "@" + modelName;
        final String shared = "thinkingMode {} asks for the {} thinking dialect and {} speaks {}, which rejects the "
                + "other one with HTTP 400; the request is being translated to {} so the turn succeeds instead of "
                + "failing";
        final Integer configuredBudget = config.getThinkingBudgetTokens();
        if (configuredBudget == null) {
            resolution.record(AnthropicThinkingResolution.Finding.requiresThinking(signature,
                    shared + ", carrying the same reasoning intent. Set thinkingMode({}) to have the dialect decided "
                            + "per model rather than reported, or correct the capability row if this model really "
                            + "speaks {}.",
                    mode, named, modelName, known, known, AnthropicThinkingMode.AUTO, named));
            return Optional.of(known);
        }
        // The lossiest corner in the whole change, and it gets one warning naming both numbers rather than a silent
        // substitution: a token count has no counterpart in a dialect where the model manages its own budget.
        resolution.record(AnthropicThinkingResolution.Finding.requiresThinking(signature,
                shared + ". The configured thinking budget of {} tokens has no counterpart there — the model manages "
                        + "its own budget in that dialect — so the intent is carried as the nearest effort rung, {}. "
                        + "Set thinkingMode({}) to have the dialect decided per model rather than reported.",
                mode, named, modelName, known, known, configuredBudget,
                AnthropicThinkingBudgets.nearestEffort(configuredBudget), AnthropicThinkingMode.AUTO));
        return Optional.of(known);
    }

    /**
     * Applies the budget-beats-rung precedence and records it once, whichever dialect the request came out speaking.
     *
     * <p>
     * The precedence matches the budget dialect's own — an operator who named a number meant that number, so it wins
     * over a rung set on the call. Both keys say <em>think this hard</em>, in two vocabularies, and an operator who
     * set both almost certainly does not know that one silences the other; that is what makes this worth a line
     * rather than a mutually exclusive pair the operator has already chosen between.
     *
     * <p>
     * The message is dialect-neutral and the signature is model-free on purpose: the condition is a property of the
     * configuration pair, and the outcome — the number wins, the rung is ignored — is the same in both dialects, so
     * one line is a complete statement. This used to be raised by the budgeted branch alone, which meant an adaptive
     * request that got here by translation discarded the rung in silence.
     *
     * <p>
     * Both branches call it and neither reads a return value: on the budgeted side
     * {@link AnthropicThinkingBudgets#budgetFor} applies the same precedence itself, and on the adaptive side
     * {@link #intendedEffort} does. One recorder over two appliers is the arrangement that makes the sentence fire
     * once for either dialect without either branch having to remember it.
     */
    private void recordBudgetOverridesEffort(AnthropicThinkingResolution.Builder resolution, ReasoningEffort effort) {
        final Integer configuredBudget = config.getThinkingBudgetTokens();
        if (configuredBudget == null || effort == null) {
            return;
        }
        resolution.record(AnthropicThinkingResolution.Finding.requiresThinking(
                "thinkingBudgetOverridesEffort=" + configuredBudget + "@" + effort,
                "Both a thinking budget ({} tokens) and a reasoning effort ({}) are set; the explicit budget wins "
                        + "and the effort is ignored on this provider.",
                configuredBudget, effort));
    }

    /**
     * The neutral rung this request's thinking intent spells, or {@code null} when nobody stated one.
     *
     * <p>
     * Usually just the call's {@link ReasoningEffort}. It differs in exactly one shape, and {@link AnthropicConfig}
     * is what makes that true: an explicit {@code thinkingBudgetTokens} is accepted only under
     * {@link AnthropicThinkingMode#EXTENDED}, so a configured budget can reach an <em>adaptive</em> request only by
     * having been translated there — and at that point the number cannot go on the wire and the rung nearest it is
     * what carries the intent. Everywhere else this reads a {@code null} budget and returns the call's effort
     * unchanged, which is what keeps an untranslated request byte-identical.
     */
    private ReasoningEffort intendedEffort(ReasoningEffort effort) {
        final Integer configuredBudget = config.getThinkingBudgetTokens();
        if (configuredBudget != null) {
            return AnthropicThinkingBudgets.nearestEffort(configuredBudget);
        }
        return effort;
    }

    /**
     * The effort somebody configured for this request: the call's {@link LlmModel} first, then the client config.
     *
     * <p>
     * One helper because there are two readers — {@link #resolve} decides whether the request carries thinking at
     * all, {@link #intendedEffort} supplies the rung a translation warning names — and a precedence applied in one of
     * them and not the other is a gate and a warning disagreeing about the same request. It is the shape
     * {@code OpenAiRequestParameters.requestedEffort(modelConfig, config)} has on the other provider, and it has it
     * for the same reason: {@code reasoningEffort} is one shared configuration key, so it resolves the same way
     * whichever client reads it. The two are deliberately not shared code — two lines over two unrelated config types
     * in two modules — and what keeps them in step is a test on each provider asserting the same precedence.
     */
    private Optional<ReasoningEffort> requestedEffort(LlmModel modelConfig) {
        return modelConfig.getReasoningEffort().or(config::getReasoningEffort);
    }

    /**
     * Records once that a configured effort reaches nothing, because this client sends no thinking parameter at all.
     *
     * <p>
     * {@code reasoningEffort} is settable deployment-wide from both configuration surfaces, and
     * {@link AnthropicThinkingMode#OFF} is the shipped default — so "make it think harder" is a reasonable thing to
     * write and, on its own, does nothing here. That is the <em>configured and never read</em> state this repository
     * refuses to leave silent, and a divergence report is the instrument this client already owns for it. Not a
     * refusal: the remedy is a <em>second</em> key, and failing the boot of a deployment for a combination whose fix
     * is another setting turns valid configuration into a startup failure.
     *
     * <p>
     * {@link ReasoningEffort#NONE} is excluded, and that exclusion is the whole of the condition's correctness.
     * {@code NONE} under {@code OFF} is not an inconsistency — both mean "send no thinking parameter", and
     * {@link #resolve} would answer the same way for either — so telling that operator to turn thinking on would be
     * advice in the wrong direction. It is the rule stated generally in §3.2 of the reporting contract: when the only
     * remedy is <em>undo the other thing you asked for</em>, the two keys are a mutually exclusive pair the operator
     * has already chosen between rather than an incomplete configuration.
     */
    private void recordInertEffort(AnthropicThinkingResolution.Builder resolution, ReasoningEffort effort) {
        if (effort == null || effort == ReasoningEffort.NONE) {
            return;
        }
        resolution.record(AnthropicThinkingResolution.Finding.explainsAbsence(
                "reasoningEffortWithThinkingOff=" + effort,
                "reasoningEffort {} is configured but thinkingMode is {}, so this request carries no thinking "
                        + "parameter and the effort reaches nothing. Set the thinking mode ({}, {} or {}) to act on "
                        + "it — note that a model whose thinking is on by default still thinks regardless.",
                effort, AnthropicThinkingMode.OFF, AnthropicThinkingMode.AUTO, AnthropicThinkingMode.ADAPTIVE,
                AnthropicThinkingMode.EXTENDED));
    }

    /**
     * Records once that {@code thinkingDisplay} is inert under {@link AnthropicThinkingMode#OFF}.
     *
     * <p>
     * The same shape and the same reasoning as {@link #recordInertEffort}: with no {@code thinking} parameter on the
     * request there is no {@code display} to carry and no {@code thinking_delta} to forward, so an operator who set
     * the key sees nothing at all and has no other signal that the two settings disagree.
     *
     * <p>
     * <strong>There is a fourth inert pair and it is deliberately not reported.</strong> A display configured
     * alongside {@link ReasoningEffort#NONE} also reaches nothing — {@link #resolve} returns on that value before
     * this method runs — and the silence is derived rather than remembered: the only remedy is <em>stop asking for no
     * reasoning</em>, which reverses a value the operator explicitly wrote, and no false conclusion is available
     * because absent thinking text is exactly what {@code NONE} asked for. Both clauses of §3.2's rule fail, so
     * nothing is said. The three conditions this client does report are ones where the two settings disagree; this
     * one is a pair that agrees.
     */
    private void recordInertDisplay(AnthropicThinkingResolution.Builder resolution) {
        if (config.getThinkingDisplay().isEmpty()) {
            return;
        }
        resolution.record(AnthropicThinkingResolution.Finding.explainsAbsence(
                "thinkingDisplayWithThinkingOff=" + config.getThinkingDisplay().get(),
                "thinkingDisplay {} is configured but thinkingMode is {}, so this request carries no thinking "
                        + "parameter, no display reaches the server and no reasoning text reaches the stream. Set the "
                        + "thinking mode ({}, {} or {}) to act on it.",
                config.getThinkingDisplay().get(), AnthropicThinkingMode.OFF, AnthropicThinkingMode.ADAPTIVE,
                AnthropicThinkingMode.EXTENDED, AnthropicThinkingMode.AUTO));
    }

    /**
     * Records once that {@code thinkingDisplay} put nothing on a budgeted request, while still doing its other half.
     *
     * <p>
     * The half that works is the forwarding: on this dialect {@code thinking_delta} events already arrive and the key
     * is what lets them reach the sink, so a watcher <em>does</em> see thinking text. The half that does not is the
     * word itself — {@code ThinkingConfigEnabled} is not given a {@code display} sibling (see
     * {@link AnthropicThinkingDisplay}), so the {@code summarized} the operator wrote reached nothing. Without this
     * line, working output would read as proof the field went out.
     *
     * <p>
     * {@code REQUIRES_THINKING}, and this record is why that classification is not a formality: the budgeted branch
     * can still fail to find a legal budget after this runs, and until the resolution decided which findings survive,
     * this sentence went out about requests that carried no {@code thinking} key at all.
     */
    private void recordDisplayOnBudgetedDialect(AnthropicThinkingResolution.Builder resolution) {
        if (config.getThinkingDisplay().isEmpty()) {
            return;
        }
        resolution.record(AnthropicThinkingResolution.Finding.requiresThinking(
                "thinkingDisplayOnBudgetedDialect=" + config.getThinkingDisplay().get(),
                "thinkingDisplay {} is configured but this request speaks the {} dialect, which is not given a "
                        + "`display` field, so the value itself reaches nothing. Thinking text still streams — the "
                        + "budgeted dialect emits it regardless and the key is what forwards it — so the visible "
                        + "output is not evidence that the field went out.",
                config.getThinkingDisplay().get(), ThinkingDialect.BUDGETED));
    }

    private AnthropicThinkingResolution resolveExtendedThinking(AnthropicThinkingResolution.Builder resolution,
            ReasoningEffort effort, int maxTokens) {
        recordBudgetOverridesEffort(resolution, effort);

        final Integer configuredBudget = config.getThinkingBudgetTokens();
        final OptionalInt budget = AnthropicThinkingBudgets.budgetFor(effort, configuredBudget, maxTokens);
        if (budget.isEmpty()) {
            // Thinking tokens count against max_tokens, so below ~1024 there is no legal budget at all. Sending a
            // request the server is certain to reject is worse than not asking for thinking.
            resolution.record(AnthropicThinkingResolution.Finding.explainsAbsence(
                    "thinkingBudgetImpossible=" + maxTokens,
                    "maxTokens is {}, which leaves no room for the {}-token minimum thinking budget; the thinking "
                            + "parameter is being omitted and the call will succeed without extended thinking.",
                    maxTokens, AnthropicThinkingBudgets.MINIMUM_BUDGET_TOKENS));
            return resolution.build();
        }

        final int requested = AnthropicThinkingBudgets.requestedBudget(effort, configuredBudget);
        final int resolved = budget.getAsInt();
        if (resolved != requested) {
            // What is lost here is the *answer*, not the thinking. Thinking tokens count against max_tokens, so a
            // budget clamped to maxTokens - 1 leaves one token for visible output and the turn ends on
            // stop_reason: max_tokens. Naming the reduced budget instead would point the operator at the harmless
            // half of the change.
            resolution.record(AnthropicThinkingResolution.Finding.requiresThinking(
                    "thinkingBudgetClamped=" + requested + "->" + resolved,
                    "A thinking budget of {} tokens does not fit under maxTokens {}; sending {} instead, which leaves "
                            + "only {} tokens for the visible answer. Raise maxTokens: thinking tokens are counted "
                            + "against it, so the reply is what this squeezes out, not the reasoning.",
                    requested, maxTokens, resolved, maxTokens - resolved));
        }
        final ThinkingConfigEnabled enabled = ThinkingConfigEnabled.builder().budgetTokens(resolved).build();
        return resolution.parameter(ThinkingConfigParam.ofEnabled(enabled)).build();
    }
}
