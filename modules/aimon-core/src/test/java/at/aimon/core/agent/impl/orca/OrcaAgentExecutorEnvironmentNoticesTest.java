package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.environment.ExecutionEnvironment;
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
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;
import at.aimon.core.tools.bash.BashTool;
import at.aimon.core.tools.file.ReadTool;

/**
 * An environment's queued notices reach the model through the agent's real tool loop, on whichever tool result comes
 * next — a turn that only reads files hears them too (issue #254).
 */
@DisplayName("OrcaAgentExecutor environment notices")
class OrcaAgentExecutorEnvironmentNoticesTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem fileSystem;
    private final Queue<String> notices = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "alpha\n");
        fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
    }

    @Test
    @DisplayName("a file-only turn hears the notice on its first Read, and the next Read does not repeat it")
    void fileOnlyTurnHearsTheNoticeOnce() {
        final ScriptedLlmClient llm = new ScriptedLlmClient(read("r1"), read("r2"),
                LlmResponse.of("done", List.of(), TokenUsage.of(1, 1, 2)));
        notices.add("The sandbox was re-created; /workspace is empty.");

        final OrcaAgentExecutionResult result = executor(llm).execute(runtime(null, new ReadTool()),
                OrcaAgentExecutionRequest.builder().userInput("look at a.txt").sessionId(SessionId.generate()).build());

        assertThat(result.isSuccess()).isTrue();
        final ToolUseResult first = llm.lastToolResult(1);
        final ToolUseResult second = llm.lastToolResult(2);
        assertThat(first.getContent()).startsWith("[environment] The sandbox was re-created; /workspace is empty.\n")
                .contains("alpha");
        assertThat(second.getContent()).doesNotContain("[environment]").contains("alpha");
    }

    @Test
    @DisplayName("on a Bash result the environment's own notices come first, then the command's, then its output")
    void bashResultReadsDrainedNoticesThenCommandNotices() {
        final ScriptedLlmClient llm = new ScriptedLlmClient(LlmResponse.of("run",
                List.of(ToolUse.of("b1", BashTool.TOOL_NAME, Map.of("command", "ls"))), TokenUsage.of(1, 1, 2)),
                LlmResponse.of("done", List.of(), TokenUsage.of(1, 1, 2)));
        notices.add("environment was recreated");
        final VirtualShell shell = new FixedShell(new ShellCommandResult(0, "out\n", "", Duration.ofMillis(1), false,
                List.of("shell session was reopened; cwd was reset")));

        executor(llm).execute(runtime(shell, new BashTool()),
                OrcaAgentExecutionRequest.builder().userInput("ls").sessionId(SessionId.generate()).build());

        assertThat(llm.lastToolResult(1).getContent()).isEqualTo("[environment] environment was recreated\n"
                + "[environment] shell session was reopened; cwd was reset\n" + "out\n");
    }

    private static LlmResponse read(String toolUseId) {
        return LlmResponse.of("reading",
                List.of(ToolUse.of(toolUseId, ReadTool.TOOL_NAME, Map.of("file_path", "a.txt"))),
                TokenUsage.of(1, 1, 2));
    }

    private OrcaAgentRuntime runtime(VirtualShell shell, at.aimon.core.agent.tool.Tool tool) {
        final TestExecutionEnvironments.Builder builder = TestExecutionEnvironments.builder().fileSystem(fileSystem)
                .notices(notices);
        if (shell != null) {
            builder.shell(shell);
        }
        final ExecutionEnvironment environment = builder.build();
        final DefaultToolRegistry tools = new DefaultToolRegistry();
        tools.register(tool);
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(10).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(tools).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).controlFileSystem(fileSystem)
                .executionEnvironmentProvider(request -> request.parent().orElse(environment)).build();
    }

    private static OrcaAgentExecutor executor(LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, new DefaultCommandExecutionManager(client),
                new DefaultSubagentExecutionManager(client, toolManager, hookManager));
    }

    /** Answers the scripted responses in order and records every request's messages. */
    private static final class ScriptedLlmClient implements LlmClient {
        private final List<LlmResponse> responses;
        private final List<List<Message>> requests = new ArrayList<>();

        ScriptedLlmClient(LlmResponse... responses) {
            this.responses = new ArrayList<>(List.of(responses));
        }

        /** The tool result the {@code call}-th request (0-based) ended with. */
        ToolUseResult lastToolResult(int call) {
            final List<Message> messages = requests.get(call);
            final List<ToolUseResult> results = messages.get(messages.size() - 1).getToolUseResults();
            assertThat(results).hasSize(1);
            return results.get(0);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            requests.add(List.copyOf(messages));
            return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.remove(0);
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }

    /** A shell that answers every command with one fixed result. */
    private static final class FixedShell implements VirtualShell {
        private final ShellCommandResult result;

        FixedShell(ShellCommandResult result) {
            this.result = result;
        }

        @Override
        public ShellCommandResult execute(ShellCommand command) {
            return result;
        }

        @Override
        public ShellCommandResult execute(ShellCommand command, ExecutionOptions options) {
            return result;
        }

        @Override
        public String getWorkingDirectory() {
            return null;
        }

        @Override
        public boolean supports(ShellFeature feature) {
            return false;
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
