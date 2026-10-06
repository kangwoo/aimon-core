package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.ReasoningSummary;

/**
 * The precedence of the reasoning summary request: agent definition, then deployment key, then client default.
 *
 * <p>
 * {@link OpenAiRequestParameters#requestedSummary} is the one answer the request's {@code reasoning.summary}, the
 * stream's reasoning-delta gate and the inert-key reports all read, so these rows cover the gate as well as the wire.
 */
@DisplayName("OpenAiRequestParameters.requestedSummary - agent value over deployment key over client default")
class OpenAiRequestedSummaryTest {

    private static OpenAIConfig deployment(OpenAiReasoningSummary summary) {
        final OpenAIConfig.Builder builder = OpenAIConfig.builder().apiKey("k").model("gpt-5-mini");
        if (summary != null) {
            builder.reasoningSummary(summary);
        }
        return builder.build();
    }

    private static LlmModel agent(ReasoningSummary summary) {
        return LlmModel.builder().reasoningSummary(summary).build();
    }

    @Test
    @DisplayName("neither set: the client default, which asks for nothing")
    void neitherSetAsksForNothing() {
        assertThat(OpenAiRequestParameters.requestedSummary(LlmModel.builder().build(), deployment(null))).isEmpty();
    }

    @Test
    @DisplayName("only the deployment key set: its level")
    void theDeploymentKeyAppliesWhenTheAgentSaysNothing() {
        assertThat(OpenAiRequestParameters.requestedSummary(LlmModel.builder().build(),
                deployment(OpenAiReasoningSummary.CONCISE))).contains(OpenAiReasoningSummary.CONCISE);
    }

    @Test
    @DisplayName("agent none: nothing, with or without the deployment key")
    void anAgentNoneAsksForNothing() {
        assertThat(OpenAiRequestParameters.requestedSummary(agent(ReasoningSummary.NONE),
                deployment(OpenAiReasoningSummary.DETAILED))).isEmpty();
        assertThat(OpenAiRequestParameters.requestedSummary(agent(ReasoningSummary.NONE), deployment(null))).isEmpty();
    }

    @Test
    @DisplayName("agent level: that level, with or without the deployment key")
    void anAgentLevelWins() {
        assertThat(OpenAiRequestParameters.requestedSummary(agent(ReasoningSummary.AUTO), deployment(null)))
                .contains(OpenAiReasoningSummary.AUTO);
        assertThat(OpenAiRequestParameters.requestedSummary(agent(ReasoningSummary.CONCISE),
                deployment(OpenAiReasoningSummary.DETAILED))).contains(OpenAiReasoningSummary.CONCISE);
        assertThat(OpenAiRequestParameters.requestedSummary(agent(ReasoningSummary.DETAILED),
                deployment(OpenAiReasoningSummary.AUTO))).contains(OpenAiReasoningSummary.DETAILED);
    }

    @Test
    @DisplayName("every neutral level has a vendor level under the same name, and the vendor key has no other")
    void theTwoVocabulariesDifferByNoneOnly() {
        for (ReasoningSummary summary : ReasoningSummary.values()) {
            if (summary == ReasoningSummary.NONE) {
                continue;
            }
            assertThat(OpenAiRequestParameters.requestedSummary(agent(summary), deployment(null))).map(Enum::name)
                    .contains(summary.name());
        }
        assertThat(OpenAiReasoningSummary.values()).hasSize(ReasoningSummary.values().length - 1);
    }
}
