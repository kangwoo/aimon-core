package at.aimon.core.subagent.execution;

import java.util.Objects;

import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.Subagent;

/**
 * Shared resolution of the LLM model and usage-attribution metadata for subagent execution.
 *
 * <p>
 * Both the ReAct path ({@link DefaultSubagentExecutor}) and the code-behavior path
 * ({@code at.aimon.core.subagent.behavior.SubagentBehaviorRunner}) resolve these identically, so the logic lives here
 * once
 * —
 * a change made in lockstep rather than duplicated in two files.
 */
public final class SubagentLlmDefaults {

    private static final int DEFAULT_MAX_TOKENS = 4096;

    private SubagentLlmDefaults() {
    }

    /**
     * Resolves the model for a subagent: the subagent's own {@code model} name when set, otherwise the default model's
     * name, merged with the default's sampling parameters and max-tokens. This is the model the ReAct path sends to the
     * LLM.
     *
     * <p>
     * When neither names a model, the result carries no name, and the client sends its own default model — exactly as
     * it does for a main agent whose definition names none.
     *
     * @param subagent
     *            the subagent (must not be null)
     * @param defaultModel
     *            the default model config (must not be null)
     * @return the resolved model (never null; its name may be empty)
     */
    public static LlmModel resolveModel(Subagent subagent, LlmModel defaultModel) {
        return resolveModel(subagent, defaultModel, null);
    }

    /**
     * Resolves the model for a subagent with a caller-supplied per-invocation override taking top priority.
     *
     * <p>
     * Priority (highest first): explicit {@code modelOverride} (e.g. the {@code Task} tool's {@code model} argument)
     * &gt; the subagent's own {@code model} frontmatter name &gt; the default model's name. Every one of them is a
     * model name sent as written — nothing here resolves an alias. When none is present the result carries no name,
     * and the client applies its own default model at request time rather than a name invented here. Max-tokens and
     * the sampling parameters are always inherited from {@code defaultModel} — only the model name is overridden.
     *
     * <p>
     * <b>A sampling parameter the spawning agent does not state stays unset.</b> {@code temperature}, {@code topP} and
     * the two penalties are carried when the parent's model has them and left out when it does not, so the provider's
     * configured default (and after it the server's) applies to a fork exactly as it does to the parent's own
     * requests. Nothing is invented here: a value nobody wrote would win over the deployment's default and would be
     * sent to — or reported as suppressed on — a model the operator never configured it for.
     *
     * <p>
     * The spawning agent's {@code reasoningEffort} and {@code reasoningSummary} are inherited as well,
     * {@link at.aimon.core.llm.ReasoningEffort#NONE} and {@link at.aimon.core.llm.ReasoningSummary#NONE} included: a
     * subagent definition names its model as a bare string and has nowhere to state either, so without this a fork
     * would follow the deployment's setting where its parent had overridden it. Each is left unset when the parent
     * states none. Whether the subagent's model can carry the value is still decided by the provider, from that
     * model's capabilities: an effort that is not on the subagent model's ladder is omitted and reported, as a
     * deployment default would be, never sent as it is.
     *
     * @param subagent
     *            the subagent (must not be null)
     * @param defaultModel
     *            the default model config (must not be null)
     * @param modelOverride
     *            the per-invocation model name; when {@code null} or blank the override is ignored and resolution
     *            falls back to the subagent/default chain
     * @return the resolved model (never null; its name may be empty)
     */
    public static LlmModel resolveModel(Subagent subagent, LlmModel defaultModel, String modelOverride) {
        Objects.requireNonNull(subagent, "subagent cannot be null");
        Objects.requireNonNull(defaultModel, "defaultModel cannot be null");
        final String subagentModel = subagent.getMetadata().getModel();
        final String modelName;
        if (modelOverride != null && !modelOverride.isBlank()) {
            modelName = modelOverride;
        } else if (subagentModel != null && !subagentModel.isEmpty()) {
            modelName = subagentModel;
        } else {
            // No literal: a nameless model is sent on the client's own default, as a nameless main agent already is.
            modelName = defaultModel.getName().orElse(null);
        }
        final LlmModel.Builder model = LlmModel.builder().name(modelName)
                .maxTokens(defaultModel.getMaxTokens().orElse(DEFAULT_MAX_TOKENS))
                .reasoningEffort(defaultModel.getReasoningEffort().orElse(null))
                .reasoningSummary(defaultModel.getReasoningSummary().orElse(null));
        defaultModel.getTemperature().ifPresent(model::temperature);
        defaultModel.getTopP().ifPresent(model::topP);
        defaultModel.getPresencePenalty().ifPresent(model::presencePenalty);
        defaultModel.getFrequencyPenalty().ifPresent(model::frequencyPenalty);
        return model.build();
    }

    /**
     * Builds the subagent-attributed LLM call metadata: component = subagent name, feature = {@code "subagent"}, the
     * parent component preserved for hierarchical attribution, and other fields (traceId, principal, tags) inherited
     * from the parent metadata.
     *
     * @param subagentName
     *            the subagent name (must not be null)
     * @param parentMetadata
     *            the parent's metadata (must not be null; use {@link LlmCallMetadata#empty()} if none)
     * @return the effective metadata (never null)
     */
    public static LlmCallMetadata effectiveMetadata(String subagentName, LlmCallMetadata parentMetadata) {
        Objects.requireNonNull(subagentName, "subagentName cannot be null");
        Objects.requireNonNull(parentMetadata, "parentMetadata cannot be null");
        final String parentComponent = parentMetadata.getComponent().orElse(null);
        return LlmCallMetadata.builder().component(subagentName).parentComponent(parentComponent).feature("subagent")
                .build().withDefaults(parentMetadata);
    }
}
