package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ReasoningSummary;

class AgentDefinitionVersionTest {

    /** Recorded from the tree before {@code model.reasoningSummary} existed. */
    private static final String VERSION_BEFORE_REASONING_SUMMARY = "48b1808b2ef83048";

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
    void changedAttributeChangesVersion() {
        final Agent build = DefaultAgent.builder().name("a").systemPrompt("p")
                .attributes(Map.of("sandbox.slot", "build")).build();
        final Agent test = DefaultAgent.builder().name("a").systemPrompt("p").attributes(Map.of("sandbox.slot", "test"))
                .build();

        assertThat(AgentDefinitionVersion.from(build)).isNotEqualTo(AgentDefinitionVersion.from(test));
    }

    @Test
    void noAttributesKeepsTheDigestOfADefinitionWrittenBeforeAttributesExisted() {
        final Agent none = DefaultAgent.builder().name("a").systemPrompt("p").build();
        final Agent empty = DefaultAgent.builder().name("a").systemPrompt("p").attributes(Map.of()).build();

        assertThat(AgentDefinitionVersion.from(none)).isEqualTo(AgentDefinitionVersion.from(empty));
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
    void changedReasoningEffortChangesVersion() {
        // canonicalForm enumerates every LlmModel field, one line each: a field left out of it makes two definitions
        // that differ only in that field digest identically, i.e. the change detector reports "unchanged" about a
        // definition that changed.
        final Agent low = DefaultAgent.builder().name("a").systemPrompt("p")
                .model(LlmModel.builder().name("m").reasoningEffort(ReasoningEffort.LOW).build()).build();
        final Agent high = DefaultAgent.builder().name("a").systemPrompt("p")
                .model(LlmModel.builder().name("m").reasoningEffort(ReasoningEffort.HIGH).build()).build();
        final Agent unset = DefaultAgent.builder().name("a").systemPrompt("p")
                .model(LlmModel.builder().name("m").build()).build();

        assertThat(AgentDefinitionVersion.from(low)).isNotEqualTo(AgentDefinitionVersion.from(high));
        assertThat(AgentDefinitionVersion.from(low)).isNotEqualTo(AgentDefinitionVersion.from(unset));
    }

    @Test
    void changedReasoningSummaryChangesVersion() {
        final Agent none = DefaultAgent.builder().name("a").systemPrompt("p")
                .model(LlmModel.builder().name("m").reasoningSummary(ReasoningSummary.NONE).build()).build();
        final Agent detailed = DefaultAgent.builder().name("a").systemPrompt("p")
                .model(LlmModel.builder().name("m").reasoningSummary(ReasoningSummary.DETAILED).build()).build();
        final Agent unset = DefaultAgent.builder().name("a").systemPrompt("p")
                .model(LlmModel.builder().name("m").build()).build();

        assertThat(AgentDefinitionVersion.from(none)).isNotEqualTo(AgentDefinitionVersion.from(detailed));
        // none is a statement, not the absence of one: it overrides a deployment that asks for a summary.
        assertThat(AgentDefinitionVersion.from(none)).isNotEqualTo(AgentDefinitionVersion.from(unset));
    }

    /**
     * The line is written only when the key is set, so a definition that does not use the key keeps the version it
     * had before the key existed — a scheduled task recorded against it does not report a drift that did not happen.
     */
    @Test
    void aDefinitionWithoutAReasoningSummaryKeepsItsVersion() {
        final Agent agent = DefaultAgent.builder().name("orca").systemPrompt("You are Orca.")
                .model(LlmModel.builder().name("gpt-5.1").build()).build();

        assertThat(AgentDefinitionVersion.from(agent).value()).isEqualTo(VERSION_BEFORE_REASONING_SUMMARY);
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
