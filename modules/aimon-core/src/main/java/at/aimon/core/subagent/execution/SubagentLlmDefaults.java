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

    private static final double DEFAULT_TEMPERATURE = 0.7;
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private SubagentLlmDefaults() {
    }

    /**
     * Resolves the model for a subagent: the subagent's own {@code model} name when set, otherwise the default model's
     * name, merged with the default's temperature and max-tokens. This is the model the ReAct path sends to the LLM.
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
     * and the client applies its own default model at request time rather than a name invented here. Temperature and
     * max-tokens are always inherited from {@code defaultModel} — only the model name is overridden.
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
        return LlmModel.builder().name(modelName).temperature(defaultModel.getTemperature().orElse(DEFAULT_TEMPERATURE))
                .maxTokens(defaultModel.getMaxTokens().orElse(DEFAULT_MAX_TOKENS)).build();
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
