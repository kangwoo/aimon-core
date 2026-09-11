package at.aimon.cli.repl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.cli.config.CliSettings;
import at.aimon.cli.factory.AgentSetupFactory;
import at.aimon.core.agent.Agent;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.command.CommandRegistry;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.SubagentRegistry;

/**
 * The two startup banner lines #106 is about: which bundle loaded, and which model the main agent's requests carry.
 * Both are static helpers so they can be checked without the terminal {@code start()} opens.
 *
 * <p>
 * The helpers are checked directly. {@link DisplayAgentInfo} checks that the banner really prints what they return,
 * which no helper test can show: deleting either call, or the constructor's read of the bundle name, leaves every
 * helper test green (#118).
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
        return client("OpenAI", defaultModel);
    }

    private static LlmClient client(String providerName, String defaultModel) {
        return new LlmClient() {

            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                    LlmModel modelConfig) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public String getProviderName() {
                return providerName;
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

    /** Keeps each info line instead of printing it. */
    private static final class RecordingFormatter extends OutputFormatter {

        private final List<String> info = new ArrayList<>();

        RecordingFormatter() {
            super(new CliSettings());
        }

        @Override
        public void displayInfo(String message) {
            info.add(message);
        }
    }

    /**
     * A session built as {@code ReplSessionRetryTest} builds one, with a runtime that answers every read the banner
     * makes, so {@code displayAgentInfo} runs its real calls on the real constructor's fields.
     */
    private static ReplSession session(String bundleName, Agent agent, LlmClient client, OutputFormatter formatter) {
        final OrcaAgentRuntime runtime = mock(OrcaAgentRuntime.class);
        when(runtime.getWorkflowRunner()).thenReturn(Optional.empty());
        when(runtime.getEnvironment()).thenReturn(Environment.createWithWorkingDirectory("/work"));
        when(runtime.getToolRegistry()).thenReturn(mock(ToolRegistry.class));
        when(runtime.getCommandRegistry()).thenReturn(mock(CommandRegistry.class));
        when(runtime.getSubagentRegistry()).thenReturn(mock(SubagentRegistry.class));
        when(runtime.getSkillRegistry()).thenReturn(mock(SkillRegistry.class));
        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        when(executor.getLlmClient()).thenReturn(client);

        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder().agentExecutor(executor)
                .agentRuntime(runtime).agent(agent).agentBundleName(bundleName).outputFormatter(formatter)
                .messageQueueManager(new DefaultMessageQueueManager(new InMemoryMessageQueueRepository()))
                .liveSession(mock(LiveSession.class)).build();
        return new ReplSession(agentSetup, new CliSettings(), null);
    }

    @Nested
    @DisplayName("displayAgentInfo prints the banner lines")
    class DisplayAgentInfo {

        private static final List<String> PREFIXES = List.of("Working Directory:", "Agent bundle:", "LLM Provider:");

        // The client's default is not the definition's model, so the provider line shows the definition's only if
        // displayAgentInfo hands the agent to providerLine -- #106's own bug, one argument away.
        private final Agent agent = agent("default-agent", "claude-sonnet-4-5");
        private final LlmClient client = client("Anthropic", "claude-haiku-4-5");

        private List<String> infoLinesFor(String bundleName) {
            final RecordingFormatter formatter = new RecordingFormatter();
            session(bundleName, agent, client, formatter).displayAgentInfo();
            return formatter.info;
        }

        @Test
        @DisplayName("prints the bundle line, from the bundle name the constructor read")
        void printsTheBundleLine() {
            assertThat(infoLinesFor("default-anthropic"))
                    .contains("Agent bundle: default-anthropic (agent name: default-agent)");
        }

        @Test
        @DisplayName("prints the provider line with the definition's model, not the client's default")
        void printsTheProviderLine() {
            assertThat(infoLinesFor("default-anthropic")).contains("LLM Provider: Anthropic (claude-sonnet-4-5)")
                    .doesNotContain("LLM Provider: Anthropic (claude-haiku-4-5)");
        }

        @Test
        @DisplayName("prints the working directory, then the bundle, then the provider")
        void printsThemInOrder() {
            final List<String> prefixes = infoLinesFor("default-anthropic").stream()
                    .map(line -> PREFIXES.stream().filter(line::startsWith).findFirst().orElse("")).toList();

            assertThat(prefixes).containsSubsequence(PREFIXES);
        }

        @Test
        @DisplayName("prints no bundle line for a setup without a bundle name, and still the provider line")
        void noBundleNameStillPrintsTheProvider() {
            assertThat(infoLinesFor(null)).noneMatch(line -> line.startsWith("Agent bundle:"))
                    .anyMatch(line -> line.startsWith("LLM Provider:"));
        }
    }
}
