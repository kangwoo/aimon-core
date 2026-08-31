package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmModel;

class AgentDefinitionVersionTest {

    @Test
    void sameDefinitionYieldsSameVersion() {
        assertThat(AgentDefinitionVersion.from(agent("prompt")))
                .isEqualTo(AgentDefinitionVersion.from(agent("prompt")));
    }

    @Test
    void versionIsSixteenHexCharacters() {
        final String value = AgentDefinitionVersion.from(agent("prompt")).value();

        assertThat(value).hasSize(16).matches("[0-9a-f]{16}");
    }

    @Test
    void changedSystemPromptChangesVersion() {
        assertThat(AgentDefinitionVersion.from(agent("prompt")))
                .isNotEqualTo(AgentDefinitionVersion.from(agent("prompt "))); // one trailing space
    }

    @Test
    void changedMaxIterationsChangesVersion() {
        final Agent base = DefaultAgent.builder().name("a").systemPrompt("p").maxIterations(5).build();
        final Agent more = DefaultAgent.builder().name("a").systemPrompt("p").maxIterations(6).build();

        assertThat(AgentDefinitionVersion.from(base)).isNotEqualTo(AgentDefinitionVersion.from(more));
    }

    @Test
    void changedModelChangesVersion() {
        final Agent cool = DefaultAgent.builder().name("a").systemPrompt("p")
                .model(LlmModel.builder().name("m").temperature(0.1).build()).build();
        final Agent warm = DefaultAgent.builder().name("a").systemPrompt("p")
                .model(LlmModel.builder().name("m").temperature(0.9).build()).build();

        assertThat(AgentDefinitionVersion.from(cool)).isNotEqualTo(AgentDefinitionVersion.from(warm));
    }

    @Test
    void changedNameChangesVersion() {
        final Agent a = DefaultAgent.builder().name("a").systemPrompt("p").build();
        final Agent b = DefaultAgent.builder().name("b").systemPrompt("p").build();

        assertThat(AgentDefinitionVersion.from(a)).isNotEqualTo(AgentDefinitionVersion.from(b));
    }

    @Test
    void tagOrderDoesNotAffectVersion() {
        // Two loads of the same bundle must agree even if the tag set iterates differently.
        final Agent forward = DefaultAgent.builder()
                .metadata(AgentMetadata.builder().name("a").tags(List.of("ops", "beta", "cron")).build())
                .systemPrompt("p").build();
        final Agent reversed = DefaultAgent.builder()
                .metadata(AgentMetadata.builder().name("a").tags(List.of("cron", "ops", "beta")).build())
                .systemPrompt("p").build();

        assertThat(AgentDefinitionVersion.from(forward)).isEqualTo(AgentDefinitionVersion.from(reversed));
    }

    @Test
    void addedTagChangesVersion() {
        final Agent untagged = DefaultAgent.builder().metadata(AgentMetadata.builder().name("a").build())
                .systemPrompt("p").build();
        final Agent tagged = DefaultAgent.builder().metadata(AgentMetadata.builder().name("a").tag("ops").build())
                .systemPrompt("p").build();

        assertThat(AgentDefinitionVersion.from(untagged)).isNotEqualTo(AgentDefinitionVersion.from(tagged));
    }

    @Test
    void variableOrderDoesNotAffectVersion() {
        final Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("region", "eu");
        forward.put("tier", "gold");

        final Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("tier", "gold");
        reversed.put("region", "eu");

        assertThat(AgentDefinitionVersion.from(agentWithVariables(forward)))
                .isEqualTo(AgentDefinitionVersion.from(agentWithVariables(reversed)));
    }

    @Test
    void changedVariableValueChangesVersion() {
        assertThat(AgentDefinitionVersion.from(agentWithVariables(Map.of("region", "eu"))))
                .isNotEqualTo(AgentDefinitionVersion.from(agentWithVariables(Map.of("region", "us"))));
    }

    @Test
    void ofRehydratesARecordedValue() {
        final AgentDefinitionVersion computed = AgentDefinitionVersion.from(agent("prompt"));

        assertThat(AgentDefinitionVersion.of(computed.value())).isEqualTo(computed).hasSameHashCodeAs(computed);
    }

    @Test
    void ofRejectsNullAndBlank() {
        assertThatNullPointerException().isThrownBy(() -> AgentDefinitionVersion.of(null));
        assertThatIllegalArgumentException().isThrownBy(() -> AgentDefinitionVersion.of("  "));
    }

    @Test
    void fromRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> AgentDefinitionVersion.from(null));
    }

    @Test
    void toStringIsTheValue() {
        final AgentDefinitionVersion version = AgentDefinitionVersion.from(agent("prompt"));

        assertThat(version).hasToString(version.value());
    }

    @Test
    void isNotEqualToOtherTypes() {
        assertThat(AgentDefinitionVersion.from(agent("prompt"))).isNotEqualTo("string").isNotEqualTo(null);
    }

    private static Agent agent(String systemPrompt) {
        return DefaultAgent.builder().name("a").systemPrompt(systemPrompt).build();
    }

    private static Agent agentWithVariables(Map<String, Object> variables) {
        return DefaultAgent.builder().metadata(AgentMetadata.builder().name("a").build())
                .content(AgentContent.builder().systemPrompt("p").variables(variables).build()).build();
    }
}
