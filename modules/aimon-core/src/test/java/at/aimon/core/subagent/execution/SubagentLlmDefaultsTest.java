package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.Subagent;

@DisplayName("SubagentLlmDefaults.resolveModel — model resolution priority")
class SubagentLlmDefaultsTest {

    private static final LlmModel DEFAULT_MODEL = LlmModel.builder().name("gpt-4").temperature(0.3).maxTokens(2048)
            .build();

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

        assertThat(resolved.getName()).contains("gpt-4");
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
}
