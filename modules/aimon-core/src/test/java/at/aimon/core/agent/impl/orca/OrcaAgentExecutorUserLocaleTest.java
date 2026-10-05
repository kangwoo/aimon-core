package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.UserLocale;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;
import at.aimon.core.tools.ToolContextKeys;

/**
 * What the {@link UserLocale} of a runtime does and does not reach in {@link OrcaAgentExecutor}.
 *
 * <p>
 * The first test is a characterization test, not a requirement: nothing reads {@link UserLocale#getTimeZone()} today,
 * so what the model is sent cannot depend on it. It pins that, because replacing {@code Environment} with
 * {@code UserLocale} was meant to change no prompt byte. If the time zone is ever put into the prompt
 * (execution-environment backlog EE-60), this is the test that is supposed to break.
 */
@DisplayName("OrcaAgentExecutor and the runtime's UserLocale")
class OrcaAgentExecutorUserLocaleTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("the system prompt and the messages are the same in every time zone")
    void promptDoesNotDependOnTheTimeZone() {
        // The last two are 26 hours apart (UTC+14 and UTC-12), so at no instant do they share a calendar date: a
        // prompt that rendered the date in the user's zone would differ between them whenever this runs.
        final Sent utc = send(ZoneId.of("UTC"));
        final Sent kiritimati = send(ZoneId.of("Pacific/Kiritimati"));
        final Sent bakerIsland = send(ZoneId.of("Etc/GMT+12"));

        assertThat(kiritimati.systemPrompt).isEqualTo(utc.systemPrompt);
        assertThat(bakerIsland.systemPrompt).isEqualTo(utc.systemPrompt);
        assertThat(kiritimati.messages).isEqualTo(utc.messages);
        assertThat(bakerIsland.messages).isEqualTo(utc.messages);

        assertThat(utc.systemPrompt).startsWith("You are a test agent").doesNotContain("UTC");
        // Nothing is put in front of the user's message: the framework injects no date of its own.
        assertThat(utc.messages).containsExactly("Hello");
    }

    @Test
    @DisplayName("a tool finds the runtime's UserLocale under USER_LOCALE, whose name is \"userLocale\"")
    void toolContextCarriesTheUserLocale() {
        final UserLocale userLocale = UserLocale.builder().timeZone(ZoneId.of("Asia/Seoul")).build();
        final ProbeTool probe = new ProbeTool();
        final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(probe);
        final CapturingLlmClient client = new CapturingLlmClient();
        client.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));

        createExecutor(client).execute(createRuntime(userLocale, toolRegistry),
                OrcaAgentExecutionRequest.builder().userInput("probe").sessionId(SessionId.generate()).build());

        final ToolContext captured = probe.captured.get();
        assertThat(captured).isNotNull();
        assertThat(captured.get(ToolContextKeys.USER_LOCALE)).containsSame(userLocale);
        // The key's name is public: a tool may look the value up by string.
        assertThat(captured.get("userLocale", UserLocale.class)).containsSame(userLocale);
        assertThat(captured.containsKey("environment")).isFalse();
    }

    private Sent send(ZoneId timeZone) {
        final CapturingLlmClient client = new CapturingLlmClient();
        createExecutor(client).execute(
                createRuntime(UserLocale.builder().timeZone(timeZone).build(), new DefaultToolRegistry()),
                OrcaAgentExecutionRequest.builder().userInput("Hello").sessionId(SessionId.generate()).build());
        assertThat(client.sent).hasSize(1);
        return client.sent.get(0);
    }

    private OrcaAgentRuntime createRuntime(UserLocale userLocale, DefaultToolRegistry toolRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(toolRegistry).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).controlFileSystem(fileSystem)
                .executionEnvironmentProvider(TestExecutionEnvironments.provider(fileSystem)).userLocale(userLocale)
                .build();
    }

    private OrcaAgentExecutor createExecutor(LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutorFactory().create(client,
                new DefaultTranscriptManager(new InMemorySessionRecordStore()), toolManager, hookManager,
                commandManager, subagentManager);
    }

    /** What one LLM call was sent. */
    private static final class Sent {
        private final String systemPrompt;
        private final List<String> messages;

        Sent(String systemPrompt, List<Message> messages) {
            this.systemPrompt = systemPrompt;
            this.messages = messages.stream().map(Message::getContent).toList();
        }
    }

    /** Records the first call's prompt, then replays the queued responses and finally answers with plain text. */
    private static final class CapturingLlmClient implements LlmClient {
        private final List<Sent> sent = new ArrayList<>();
        private final List<LlmResponse> responses = new ArrayList<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            if (sent.isEmpty()) {
                sent.add(new Sent(systemPrompt, messages));
            }
            return responses.isEmpty() ? LlmResponse.text("done") : responses.remove(0);
        }

        @Override
        public String getProviderName() {
            return "Capturing";
        }
    }

    /** Captures the {@link ToolContext} it is invoked with. */
    private static final class ProbeTool extends AbstractTool {
        private final AtomicReference<ToolContext> captured = new AtomicReference<>();

        ProbeTool() {
            super("Probe", "Captures its tool context", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            captured.set(context);
            return ToolResult.success("ok");
        }
    }
}
