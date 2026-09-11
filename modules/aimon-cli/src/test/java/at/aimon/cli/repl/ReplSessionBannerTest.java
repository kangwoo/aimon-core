package at.aimon.cli.repl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;

/**
 * The two startup banner lines #106 is about: which bundle loaded, and which model the main agent's requests carry.
 * Both are static helpers so they can be checked without the terminal {@code start()} opens.
 */
@DisplayName("ReplSession banner")
class ReplSessionBannerTest {

    private static Agent agent(String name, String modelName) {
        final LlmModel.Builder model = LlmModel.builder();
        if (modelName != null) {
            model.name(modelName);
        }
        return DefaultAgent.builder().name(name).systemPrompt("You are a test agent.").model(model.build()).build();
    }

    /** A client whose default model — what it would send for a request that names none — is {@code defaultModel}. */
    private static LlmClient client(String defaultModel) {
        return new LlmClient() {

            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                    LlmModel modelConfig) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public String getProviderName() {
                return "OpenAI";
            }

            @Override
            public Optional<String> getDefaultModelName() {
                return Optional.ofNullable(defaultModel);
            }
        };
    }

    @Nested
    @DisplayName("Agent bundle:")
    class AgentBundleLine {

        @Test
        @DisplayName("names the configured bundle, and the definition's name when it differs — which is the prompt's")
        void bundleAndDefinitionName() {
            assertThat(ReplSession.agentBundleLine("default-anthropic", agent("default-agent", "claude-sonnet-4-5")))
                    .contains("Agent bundle: default-anthropic (agent name: default-agent)");
        }

        @Test
        @DisplayName("leaves the parenthesis out when the two names are the same")
        void sameName() {
            assertThat(ReplSession.agentBundleLine("ops-agent", agent("ops-agent", "gpt-5.1")))
                    .contains("Agent bundle: ops-agent");
        }

        @Test
        @DisplayName("is absent for a setup that carries no bundle name")
        void noBundleName() {
            assertThat(ReplSession.agentBundleLine(null, agent("default-agent", "gpt-5.1"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("LLM Provider:")
    class ProviderLine {

        @Test
        @DisplayName("shows the definition's model.name, not the client's default (llm.model) — the shipped mismatch")
        void definitionModelWins() {
            final String line = ReplSession.providerLine(client("gpt-5.1"), agent("default-agent", "gpt-5.6-terra"));

            assertThat(line).isEqualTo("LLM Provider: OpenAI (gpt-5.6-terra)").doesNotContain("gpt-5.1");
        }

        @Test
        @DisplayName("shows the client's default when the definition names no model, as the request would carry it")
        void namelessDefinitionShowsTheClientDefault() {
            assertThat(ReplSession.providerLine(client("gpt-5.1"), agent("custom", null)))
                    .isEqualTo("LLM Provider: OpenAI (gpt-5.1)");
        }

        @Test
        @DisplayName("leaves the parenthesis out when neither names a model")
        void noModelAnywhere() {
            assertThat(ReplSession.providerLine(client(null), agent("custom", null))).isEqualTo("LLM Provider: OpenAI");
        }

        @Test
        @DisplayName("leaves the parenthesis out for a blank definition name, which the client would send as it is")
        void blankDefinitionName() {
            assertThat(ReplSession.providerLine(client("gpt-5.1"), agent("custom", "  ")))
                    .isEqualTo("LLM Provider: OpenAI");
        }
    }
}
