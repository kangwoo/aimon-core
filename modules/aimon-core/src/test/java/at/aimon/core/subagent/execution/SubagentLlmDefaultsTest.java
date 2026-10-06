package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ReasoningSummary;
import at.aimon.core.subagent.Subagent;

@DisplayName("SubagentLlmDefaults.resolveModel — model resolution priority")
class SubagentLlmDefaultsTest {

    // A name no fallback could produce on its own, so an assertion cannot pass by matching an invented literal.
    private static final LlmModel DEFAULT_MODEL = LlmModel.builder().name("parent-model").temperature(0.3)
            .maxTokens(2048).build();

    private static final LlmModel NAMELESS_DEFAULT_MODEL = LlmModel.builder().temperature(0.3).maxTokens(2048).build();

    private static Subagent subagentWithModel(String model) {
        Subagent.Builder builder = Subagent.builder().name("explore").systemPrompt("(prompt)");
        if (model != null) {
            builder.model(model);
        }
        return builder.build();
    }

    @Test
    @DisplayName("override wins over both the subagent frontmatter and the default")
    void overrideWins() {
        Subagent subagent = subagentWithModel("frontmatter-model");

        LlmModel resolved = SubagentLlmDefaults.resolveModel(subagent, DEFAULT_MODEL, "override-model");

        assertThat(resolved.getName()).contains("override-model");
    }

    @Test
    @DisplayName("a null override falls back to the subagent frontmatter model")
    void nullOverrideFallsBackToFrontmatter() {
        Subagent subagent = subagentWithModel("frontmatter-model");

        LlmModel resolved = SubagentLlmDefaults.resolveModel(subagent, DEFAULT_MODEL, null);

        assertThat(resolved.getName()).contains("frontmatter-model");
    }

    @Test
    @DisplayName("a blank override is ignored and falls back to the frontmatter model")
    void blankOverrideIgnored() {
        Subagent subagent = subagentWithModel("frontmatter-model");

        LlmModel resolved = SubagentLlmDefaults.resolveModel(subagent, DEFAULT_MODEL, "   ");

        assertThat(resolved.getName()).contains("frontmatter-model");
    }

    @Test
    @DisplayName("with no override and no frontmatter model, the default model name is used")
    void fallsBackToDefault() {
        Subagent subagent = subagentWithModel(null);

        LlmModel resolved = SubagentLlmDefaults.resolveModel(subagent, DEFAULT_MODEL, null);

        assertThat(resolved.getName()).contains("parent-model");
    }

    @Test
    @DisplayName("with no name anywhere the result is nameless, so the client sends its own default (#104)")
    void noNameAnywhereStaysNameless() {
        Subagent subagent = subagentWithModel(null);

        LlmModel resolved = SubagentLlmDefaults.resolveModel(subagent, NAMELESS_DEFAULT_MODEL, null);

        assertThat(resolved.getName()).isEmpty();
        assertThat(resolved.getTemperature()).contains(0.3);
        assertThat(resolved.getMaxTokens()).contains(2048);
    }

    @Test
    @DisplayName("a nameless default still yields the subagent's own model when it names one")
    void namelessDefaultKeepsTheSubagentModel() {
        Subagent subagent = subagentWithModel("frontmatter-model");

        LlmModel resolved = SubagentLlmDefaults.resolveModel(subagent, NAMELESS_DEFAULT_MODEL, null);

        assertThat(resolved.getName()).contains("frontmatter-model");
    }

    @Test
    @DisplayName("temperature and max-tokens are always inherited from the default model, not overridden")
    void temperatureAndMaxTokensInheritedFromDefault() {
        Subagent subagent = subagentWithModel("frontmatter-model");

        LlmModel resolved = SubagentLlmDefaults.resolveModel(subagent, DEFAULT_MODEL, "override-model");

        assertThat(resolved.getTemperature()).contains(0.3);
        assertThat(resolved.getMaxTokens()).contains(2048);
    }

    @Test
    @DisplayName("a parent that states no temperature leaves the subagent's unset, so the deployment default applies")
    void anUnsetParentTemperatureStaysUnset() {
        LlmModel parent = LlmModel.builder().name("parent-model").build();

        LlmModel resolved = SubagentLlmDefaults.resolveModel(subagentWithModel(null), parent);

        assertThat(resolved.getTemperature()).isEmpty();
    }

    @Test
    @DisplayName("the parent's topP and penalties are carried like its temperature")
    void parentSamplingParametersAreCarried() {
        LlmModel parent = LlmModel.builder().name("parent-model").temperature(0.2).topP(0.9).presencePenalty(0.5)
                .frequencyPenalty(-0.5).build();

        LlmModel resolved = SubagentLlmDefaults.resolveModel(subagentWithModel("frontmatter-model"), parent);

        assertThat(resolved.getTemperature()).contains(0.2);
        assertThat(resolved.getTopP()).contains(0.9);
        assertThat(resolved.getPresencePenalty()).contains(0.5);
        assertThat(resolved.getFrequencyPenalty()).contains(-0.5);
    }

    @Test
    @DisplayName("unset parent topP and penalties stay unset")
    void unsetParentSamplingParametersStayUnset() {
        LlmModel resolved = SubagentLlmDefaults.resolveModel(subagentWithModel(null), DEFAULT_MODEL);

        assertThat(resolved.getTopP()).isEmpty();
        assertThat(resolved.getPresencePenalty()).isEmpty();
        assertThat(resolved.getFrequencyPenalty()).isEmpty();
    }

    @Test
    @DisplayName("the two-arg overload behaves like a null override")
    void twoArgOverloadEqualsNullOverride() {
        Subagent subagent = subagentWithModel("frontmatter-model");

        assertThat(SubagentLlmDefaults.resolveModel(subagent, DEFAULT_MODEL).getName())
                .isEqualTo(SubagentLlmDefaults.resolveModel(subagent, DEFAULT_MODEL, null).getName());
    }

    @Test
    @DisplayName("null subagent or default model is rejected")
    void rejectsNulls() {
        Subagent subagent = subagentWithModel(null);
        assertThatNullPointerException().isThrownBy(() -> SubagentLlmDefaults.resolveModel(null, DEFAULT_MODEL, "x"));
        assertThatNullPointerException().isThrownBy(() -> SubagentLlmDefaults.resolveModel(subagent, null, "x"));
    }

    @Test
    @DisplayName("the spawning agent's reasoningSummary is carried onto the subagent's model, none included")
    void parentReasoningSummaryIsCarried() {
        for (ReasoningSummary summary : ReasoningSummary.values()) {
            LlmModel parent = LlmModel.builder().name("parent-model").reasoningSummary(summary).build();

            assertThat(SubagentLlmDefaults.resolveModel(subagentWithModel("frontmatter-model"), parent)
                    .getReasoningSummary()).as("frontmatter model, parent %s", summary).contains(summary);
            assertThat(SubagentLlmDefaults.resolveModel(subagentWithModel(null), parent, "override-model")
                    .getReasoningSummary()).as("override model, parent %s", summary).contains(summary);
        }
    }

    @Test
    @DisplayName("a parent that states no reasoningSummary leaves the subagent's unset, so the deployment key decides")
    void anUnsetParentReasoningSummaryStaysUnset() {
        LlmModel resolved = SubagentLlmDefaults.resolveModel(subagentWithModel("frontmatter-model"), DEFAULT_MODEL);

        assertThat(resolved.getReasoningSummary()).isEmpty();
    }

    @Test
    @DisplayName("a nested fork resolved from an already-resolved subagent model keeps the value")
    void aNestedForkKeepsTheReasoningSummary() {
        LlmModel parent = LlmModel.builder().name("parent-model").reasoningSummary(ReasoningSummary.NONE).build();
        LlmModel child = SubagentLlmDefaults.resolveModel(subagentWithModel("child-model"), parent);

        LlmModel grandchild = SubagentLlmDefaults.resolveModel(subagentWithModel("grandchild-model"), child);

        assertThat(grandchild.getReasoningSummary()).contains(ReasoningSummary.NONE);
    }

    @Test
    @DisplayName("the spawning agent's reasoningEffort is carried onto the subagent's model, none included")
    void parentReasoningEffortIsCarried() {
        for (ReasoningEffort effort : ReasoningEffort.values()) {
            LlmModel parent = LlmModel.builder().name("parent-model").reasoningEffort(effort).build();

            assertThat(SubagentLlmDefaults.resolveModel(subagentWithModel("frontmatter-model"), parent)
                    .getReasoningEffort()).as("frontmatter model, parent %s", effort).contains(effort);
            assertThat(SubagentLlmDefaults.resolveModel(subagentWithModel(null), parent, "override-model")
                    .getReasoningEffort()).as("override model, parent %s", effort).contains(effort);
        }
    }

    @Test
    @DisplayName("a parent that states no reasoningEffort leaves the subagent's unset, so the deployment key decides")
    void anUnsetParentReasoningEffortStaysUnset() {
        LlmModel resolved = SubagentLlmDefaults.resolveModel(subagentWithModel("frontmatter-model"), DEFAULT_MODEL);

        assertThat(resolved.getReasoningEffort()).isEmpty();
    }

    @Test
    @DisplayName("a nested fork keeps the reasoningEffort its grandparent stated")
    void aNestedForkKeepsTheReasoningEffort() {
        LlmModel parent = LlmModel.builder().name("parent-model").reasoningEffort(ReasoningEffort.LOW).build();
        LlmModel child = SubagentLlmDefaults.resolveModel(subagentWithModel("child-model"), parent);

        LlmModel grandchild = SubagentLlmDefaults.resolveModel(subagentWithModel("grandchild-model"), child);

        assertThat(grandchild.getReasoningEffort()).contains(ReasoningEffort.LOW);
    }
}
