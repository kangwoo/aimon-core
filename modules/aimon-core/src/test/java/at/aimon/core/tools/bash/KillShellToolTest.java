package at.aimon.core.tools.bash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.tools.ToolContextKeys;

/** Unit tests for {@link KillShellTool}. */
class KillShellToolTest {

    private static final AgentRuntimeId ACME = AgentRuntimeId.fromName("ops", "acme");
    private static final AgentRuntimeId GLOBEX = AgentRuntimeId.fromName("ops", "globex");
    private static final ExecutionOptions OPTIONS = ExecutionOptions.builder().timeout(Duration.ofHours(2))
            .background(true).build();

    private InMemoryBackgroundBashStore store;
    private BackgroundBashManager manager;
    private KillShellTool tool;

    @BeforeEach
    void setUp() {
        store = new InMemoryBackgroundBashStore();
        manager = BackgroundBashManager.builder().store(store).nodeId("node-a").build();
        tool = new KillShellTool(manager);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private static ToolContext contextOf(AgentRuntimeId runtimeId) {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, runtimeId).build();
    }

    private ToolResult kill(AgentRuntimeId caller, String taskId) {
        return tool.execute(ToolInput.of(Map.of("taskId", taskId)), contextOf(caller));
    }

    @Test
    void testConstructor_NullBackgroundManager_ThrowsException() {
        assertThatThrownBy(() -> new KillShellTool(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Background manager cannot be null");
    }

    @Test
    void testGetDefinition_NameAndSchema() {
        ToolDefinition definition = tool.getDefinition();

        assertThat(definition.getName()).isEqualTo("KillShell");
        assertThat(definition.getInputSchema()).containsEntry("required", List.of("taskId"));
        assertThat(definition.getInputSchema()).containsEntry("additionalProperties", false);
    }

    @Test
    @DisplayName("a running command whose shell can cancel is stopped, and its earlier output stays readable")
    void testExecute_RunningCancellable_Stops() throws Exception {
        ControllableShell shell = ControllableShell.cancellable();
        BackgroundBashTask task = manager.start(ACME, "npm run dev", shell, OPTIONS);
        shell.awaitStarted();

        ToolResult result = kill(ACME, task.getTaskId());

        assertThat(result.isSuccess()).as(result.getContent()).isTrue();
        assertThat(result.getContent()).contains("Shell " + task.getTaskId() + " stopped:")
                .contains("BashOutput(taskId=\"" + task.getTaskId() + "\")");
        assertThat(task.getStatus()).isEqualTo(BashTaskStatus.KILLED);

        ToolResult output = new BashOutputTool(manager).execute(ToolInput.of(Map.of("taskId", task.getTaskId())),
                contextOf(ACME));
        assertThat(output.isSuccess()).isTrue();
        assertThat(output.getContent()).contains("Status: Killed").contains(ControllableShell.PARTIAL_OUTPUT)
                .doesNotContain("Exit Code");
    }

    @Test
    @DisplayName("a running command whose shell cannot cancel is an error that names the limit it runs to")
    void testExecute_RunningUncancellable_ReturnsError() throws Exception {
        ControllableShell shell = ControllableShell.uncancellable();
        BackgroundBashTask task = manager.start(ACME, "npm run dev", shell, OPTIONS);
        shell.awaitStarted();

        ToolResult result = kill(ACME, task.getTaskId());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("cannot stop a running command").contains("2 hours");
        assertThat(task.getStatus()).isEqualTo(BashTaskStatus.RUNNING);
        shell.finish("done");
    }

    @Test
    @DisplayName("a command that already ended is a success that says nothing was stopped")
    void testExecute_AlreadyFinished_Success() {
        ControllableShell shell = ControllableShell.cancellable();
        BackgroundBashTask task = manager.start(ACME, "true", shell, OPTIONS);
        shell.finish("done");
        assertThat(task.awaitCompletion(Duration.ofSeconds(5))).isTrue();

        ToolResult result = kill(ACME, task.getTaskId());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("is not running").contains("Completed");
    }

    @Test
    @DisplayName("a command on another node is an error: only that node can stop it")
    void testExecute_OtherNode_ReturnsError() {
        store.putIfAbsent(BackgroundBashRecord.builder().taskId("bash_0000cccc").ownerRuntimeId(ACME).nodeId("node-b")
                .startedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build());

        ToolResult result = kill(ACME, "bash_0000cccc");

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("running on another node.").doesNotContain("node-b")
                .contains("cannot be stopped from here");
    }

    @Test
    @DisplayName("an unknown id and another runtime's task read the same: not found")
    void testExecute_UnknownOrForeign_ReturnsNotFound() throws Exception {
        ControllableShell shell = ControllableShell.cancellable();
        BackgroundBashTask task = manager.start(ACME, "npm run dev", shell, OPTIONS);
        shell.awaitStarted();

        ToolResult unknown = kill(ACME, "bash_ffffffff");
        ToolResult foreign = kill(GLOBEX, task.getTaskId());
        ToolResult noRuntime = tool.execute(ToolInput.of(Map.of("taskId", task.getTaskId())), ToolContext.empty());

        assertThat(unknown.isError()).isTrue();
        assertThat(unknown.getContent()).contains("Shell not found: bash_ffffffff");
        assertThat(foreign.isError()).isTrue();
        assertThat(foreign.getContent()).contains("Shell not found: " + task.getTaskId());
        assertThat(noRuntime.isError()).isTrue();
        assertThat(task.getStatus()).as("none of them reached the command").isEqualTo(BashTaskStatus.RUNNING);
    }

    @Test
    void testExecute_InvalidInput_ReturnsErrorWithoutThrowing() {
        assertThat(tool.execute(ToolInput.of(Map.of()), contextOf(ACME)).isError()).isTrue();
        assertThat(tool.execute(ToolInput.of(Map.of("taskId", "  ")), contextOf(ACME)).getContent())
                .contains("Task ID cannot be empty");
        assertThat(tool.execute(ToolInput.of(Map.of("taskId", 7)), contextOf(ACME)).isError()).isTrue();
    }

    @Test
    void testExecute_NullArguments_ThrowsException() {
        assertThatThrownBy(() -> tool.execute(null, ToolContext.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> tool.execute(ToolInput.of(Map.of("taskId", "x")), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a store that cannot be read is an error result, not an exception")
    void testExecute_StoreFailure_ReturnsError() {
        BackgroundBashStore failing = new BackgroundBashStore() {
            @Override
            public boolean putIfAbsent(BackgroundBashRecord record) {
                return true;
            }

            @Override
            public Optional<BackgroundBashRecord> find(String taskId) {
                throw new IllegalStateException("store is down");
            }

            @Override
            public Optional<BackgroundBashRecord> settle(String taskId, BashTaskStatus terminal, Integer exitCode,
                    Instant at) {
                return Optional.empty();
            }

            @Override
            public void remove(String taskId) {
                // Nothing stored.
            }
        };
        try (BackgroundBashManager broken = BackgroundBashManager.builder().store(failing).build()) {
            ToolResult result = new KillShellTool(broken).execute(ToolInput.of(Map.of("taskId", "bash_0000dddd")),
                    contextOf(ACME));

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Could not look up shell").contains("store is down");
        }
    }
}
