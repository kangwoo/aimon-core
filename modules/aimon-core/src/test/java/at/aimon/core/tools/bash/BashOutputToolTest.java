package at.aimon.core.tools.bash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.core.tools.ToolContextKeys;

/** Unit tests for {@link BashOutputTool}. */
class BashOutputToolTest {

    private BashOutputTool bashOutputTool;
    private BackgroundBashManager backgroundManager;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        backgroundManager = new BackgroundBashManager();
        bashOutputTool = new BashOutputTool(backgroundManager);
        context = ToolContext.empty();
    }

    // Constructor tests

    @Test
    void testConstructor_NullBackgroundManager_ThrowsException() {
        assertThatThrownBy(() -> new BashOutputTool(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Background manager cannot be null");
    }

    @Test
    void testConstructor_ValidBackgroundManager_Success() {
        BashOutputTool tool = new BashOutputTool(backgroundManager);
        assertThat(tool).isNotNull();
    }

    // getDefinition tests

    @Test
    void testGetDefinition_ReturnsCorrectName() {
        ToolDefinition definition = bashOutputTool.getDefinition();
        assertThat(definition.getName()).isEqualTo("BashOutput");
    }

    @Test
    void testGetDefinition_ReturnsCorrectDescription() {
        ToolDefinition definition = bashOutputTool.getDefinition();
        assertThat(definition.getDescription())
                .contains("Retrieves output from running or completed background bash shells");
        assertThat(definition.getDescription()).contains("each line is returned only once");
        // The description has to say when output appears, not just that blocking exists. A model told only that
        // block=false "returns immediately" polls a running task forever and concludes the command produced nothing.
        assertThat(definition.getDescription()).contains("Output becomes available when the command finishes");
    }

    @Test
    void testGetDefinition_HasRequiredTaskIdParameter() {
        ToolDefinition definition = bashOutputTool.getDefinition();
        Map<String, Object> schema = definition.getInputSchema();

        assertThat(schema.get("required")).asList().contains("taskId");
    }

    @Test
    void testGetDefinition_HasOptionalParameters() {
        ToolDefinition definition = bashOutputTool.getDefinition();
        Map<String, Object> schema = definition.getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKeys("taskId", "block", "wait_up_to", "filter");
    }

    // execute tests - validation

    @Test
    void testExecute_MissingTaskId_ReturnsError() {
        Map<String, Object> toolUse = Map.of();

        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter: Missing required parameter: taskId");
    }

    @Test
    void testExecute_EmptyTaskId_ReturnsError() {
        Map<String, Object> toolUse = Map.of("taskId", "   ");

        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Task ID cannot be empty");
    }

    @Test
    void testExecute_NonExistentTask_ReturnsError() {
        Map<String, Object> toolUse = Map.of("taskId", "nonexistent_task");

        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Shell not found");
        assertThat(result.getContent()).contains("nonexistent_task");
        // Must point at the id the Bash call handed back. There is no command that lists background shells, so the
        // old "use /tasks" hint sent the model looking for something that does not exist.
        assertThat(result.getContent()).contains("Use the ID returned by the Bash call");
    }

    @Test
    void testExecute_NullToolUse_ThrowsException() {
        assertThatThrownBy(() -> bashOutputTool.execute(ToolInput.of(null), context))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Input data cannot be null");
    }

    @Test
    void testExecute_NullContext_ThrowsException() {
        Map<String, Object> toolUse = Map.of("taskId", "task_123");

        assertThatThrownBy(() -> bashOutputTool.execute(ToolInput.of(toolUse), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Context cannot be null");
    }

    // execute tests - success cases (non-blocking)

    @Test
    void testExecute_RunningTask_NonBlocking_Success() {
        // Register a running task
        CompletableFuture<ShellCommandResult> future = new CompletableFuture<>();
        backgroundManager.registerTask("task_123", "echo test", future);

        Map<String, Object> toolUse = Map.of("taskId", "task_123", "block", false);

        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Status: Running");
        assertThat(result.getContent()).contains("echo test");
    }

    @Test
    void testExecute_CompletedTask_NonBlocking_Success() {
        backgroundManager.registerTask("task_123", "echo test", completedWith(0, "test output"));

        Map<String, Object> toolUse = Map.of("taskId", "task_123", "block", false);

        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Status: Completed");
        assertThat(result.getContent()).contains("Exit Code: 0");
        assertThat(result.getContent()).contains("test output");
    }

    @Test
    void testExecute_ShellThrew_ReportedAsFailed() {
        // The shell could not run the command to completion at all — that arrives as an exceptionally completed
        // future, which is the only case where there is no real exit status to report.
        CompletableFuture<ShellCommandResult> future = new CompletableFuture<>();
        future.completeExceptionally(new CompletionException(
                new ShellTimeoutException("timed out", Duration.ofSeconds(1), "partial output", "")));
        backgroundManager.registerTask("task_123", "sleep 100", future);

        Map<String, Object> toolUse = Map.of("taskId", "task_123", "block", false);

        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Status: Failed");
        // Whatever the process printed before it was killed is the only explanation the model gets for a timeout.
        assertThat(result.getContent()).contains("partial output");
    }

    @Test
    void testExecute_CompletedNotices_ShownOnceAndNotSubjectToFilter() {
        // Notices describe the run (the shell session was recreated, so cwd was reset). They sit outside the filtered
        // output — a filter that matches none of them must not hide them — and are reported once, like output.
        backgroundManager.registerTask("task_123", "make", CompletableFuture.completedFuture(new ShellCommandResult(0,
                "build ok\n", "", Duration.ofMillis(5), false, List.of("shell session was recreated; cwd was reset"))));

        ToolResult first = bashOutputTool
                .execute(ToolInput.of(Map.of("taskId", "task_123", "block", false, "filter", "ok")), context);
        ToolResult second = bashOutputTool.execute(ToolInput.of(Map.of("taskId", "task_123", "block", false)), context);

        assertThat(first.getContent()).contains("[environment] shell session was recreated; cwd was reset\n")
                .contains("build ok");
        assertThat(first.getContent().indexOf("[environment]")).isLessThan(first.getContent().indexOf("Output:"));
        assertThat(second.getContent()).doesNotContain("[environment]");
    }

    @Test
    void testExecute_TimeoutNotices_ShownOnFailedTask() {
        // The timeout path used to have no notices at all: they now ride on the exception.
        CompletableFuture<ShellCommandResult> future = new CompletableFuture<>();
        future.completeExceptionally(new CompletionException(new ShellTimeoutException("timed out",
                Duration.ofSeconds(1), "partial output", "", false, List.of("sandbox was recreated"))));
        backgroundManager.registerTask("task_123", "sleep 100", future);

        ToolResult result = bashOutputTool.execute(ToolInput.of(Map.of("taskId", "task_123", "block", false)), context);

        assertThat(result.getContent()).contains("Status: Failed").contains("[environment] sandbox was recreated\n")
                .contains("partial output");
    }

    @Test
    void testExecute_BlockingPollAfterFailure_SeesTheRecordedOutcome() throws Exception {
        // A poller woken by the raw future could read the task before the completion handler recorded the exit code,
        // output and notices. It must wait for the recorded outcome instead.
        CompletableFuture<ShellCommandResult> future = new CompletableFuture<>();
        backgroundManager.registerTask("task_123", "make", future);
        CompletableFuture<ToolResult> poll = CompletableFuture.supplyAsync(() -> bashOutputTool
                .execute(ToolInput.of(Map.of("taskId", "task_123", "block", true, "wait_up_to", 10)), context));

        Thread.sleep(50);
        future.completeExceptionally(new CompletionException(new ShellTimeoutException("timed out",
                Duration.ofSeconds(1), "partial output", "", false, List.of("sandbox was recreated"))));

        String content = poll.get(10, TimeUnit.SECONDS).getContent();
        assertThat(content).contains("Status: Failed").contains("Exit Code: 1")
                .contains("[environment] sandbox was recreated").contains("partial output");
    }

    @Test
    void testExecute_NonZeroExit_ReportedAsFailedNotCompleted() {
        // A command that exits non-zero completes its future normally, so status cannot be derived from the future
        // alone. Deriving it that way rendered a broken build as "Status: Completed / Exit Code: 127", which reads as
        // success to the model.
        backgroundManager.registerTask("task_123", "npm run build", completedWith(127, "command not found"));

        Map<String, Object> toolUse = Map.of("taskId", "task_123", "block", false);

        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Status: Failed");
        assertThat(result.getContent()).contains("Exit Code: 127");
        assertThat(result.getContent()).contains("command not found");
    }

    // execute tests - blocking mode

    @Test
    void testExecute_CompletedTask_Blocking_Success() {
        backgroundManager.registerTask("task_123", "echo test", completedWith(0, "test output\nline 2"));

        Map<String, Object> toolUse = Map.of("taskId", "task_123", "block", true);

        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Status: Completed");
        assertThat(result.getContent()).contains("Exit Code: 0");
        assertThat(result.getContent()).contains("line 2");
    }

    @Test
    void testExecute_RunningTask_Blocking_Timeout() {
        // Register a task that never completes
        CompletableFuture<ShellCommandResult> future = new CompletableFuture<>();
        backgroundManager.registerTask("task_123", "sleep 100", future);

        Map<String, Object> toolUse = Map.of("taskId", "task_123", "block", true, "wait_up_to", 1 // Wait only 1 second
        );

        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Status: Timeout");
        assertThat(result.getContent()).contains("still running");
    }

    // execute tests - read-once consumption

    @Test
    void testReadNewOutput_EachLineReturnedOnlyOnce() {
        BackgroundBashTask task = new BackgroundBashTask("task_123", "echo test",
                completedWith(0, "Line 1\nLine 2\nLine 3"));

        // The whole buffer arrives in one batch when the command finishes, so the first read gets everything...
        String first = task.readNewOutput();
        assertThat(first).contains("Line 1").contains("Line 2").contains("Line 3");

        // ...and the cursor has advanced past it. This is read-once consumption, not incremental streaming: a second
        // read is empty because nothing new was produced, not because the command is still working.
        assertThat(task.readNewOutput()).isEmpty();
    }

    // execute tests - filtering

    @Test
    void testReadNewOutput_WithFilter_OnlyMatchingLines() {
        BackgroundBashTask task = new BackgroundBashTask("task_123", "build script",
                completedWith(0, "INFO: Starting build\nERROR: Build failed\nINFO: Retrying\nWARNING: Low memory"));

        String filtered = task.readNewOutput("ERROR|WARNING");

        assertThat(filtered).contains("ERROR: Build failed");
        assertThat(filtered).contains("WARNING: Low memory");
        assertThat(filtered).doesNotContain("INFO: Starting build");
        assertThat(filtered).doesNotContain("INFO: Retrying");
    }

    // execute tests - truncation notice

    @Test
    void testExecute_TruncatedOutput_AppendsNoticeOutsideTheFilteredRegion() {
        CompletableFuture<ShellCommandResult> future = CompletableFuture
                .completedFuture(new ShellCommandResult(0, "ERROR: boom\nINFO: noise", "", Duration.ofMillis(5), true));
        backgroundManager.registerTask("task_123", "noisy command", future);

        // Filter deliberately excludes the notice's own wording: it must survive anyway, because it is appended after
        // the filter runs rather than stored as a buffered line the filter could drop.
        Map<String, Object> toolUse = Map.of("taskId", "task_123", "block", false, "filter", "ERROR");

        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("ERROR: boom").doesNotContain("INFO: noise");
        assertThat(result.getContent()).contains("[Output truncated");
    }

    @Test
    void testExecute_UntruncatedOutput_NoNotice() {
        backgroundManager.registerTask("task_123", "echo test", completedWith(0, "all of it"));

        Map<String, Object> toolUse = Map.of("taskId", "task_123", "block", false);

        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.getContent()).doesNotContain("[Output truncated");
    }

    // execute tests - timeout values

    @Test
    void testExecute_DefaultWaitUpTo_Used() {
        backgroundManager.registerTask("task_123", "echo test", completedWith(0, "output"));

        Map<String, Object> toolUse = Map.of("taskId", "task_123", "block", true);

        // Should use default wait_up_to (150 seconds)
        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void testExecute_CustomWaitUpTo_Applied() {
        CompletableFuture<ShellCommandResult> future = new CompletableFuture<>();
        backgroundManager.registerTask("task_123", "sleep 10", future);

        Map<String, Object> toolUse = Map.of("taskId", "task_123", "block", true, "wait_up_to", 1);

        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Timeout");
    }

    @Test
    void testExecute_WaitUpToExceedsMaximum_CappedAtMaximum() {
        backgroundManager.registerTask("task_123", "echo test", completedWith(0, "output"));

        Map<String, Object> toolUse = Map.of("taskId", "task_123", "block", true, "wait_up_to", 500 // Exceeds maximum
                                                                                                    // of 300
        );

        // Should be capped at 300 seconds
        ToolResult result = bashOutputTool.execute(ToolInput.of(toolUse), context);
        assertThat(result.isSuccess()).isTrue();
    }

    // Integration test with BackgroundBashManager

    // Ownership and other nodes (EE-7)

    @Test
    void testExecute_KilledTask_ReportsKilledWithItsOutput() throws Exception {
        ControllableShell shell = ControllableShell.cancellable();
        BackgroundBashTask task = backgroundManager.start(BackgroundBashOwner.none(), "npm run dev", shell,
                at.aimon.core.shell.ExecutionOptions.defaults());
        shell.awaitStarted();
        backgroundManager.kill(BackgroundBashOwner.none(), task.getTaskId());

        ToolResult result = bashOutputTool.execute(ToolInput.of(Map.of("taskId", task.getTaskId())), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Status: Killed").contains("Command: npm run dev")
                .contains(ControllableShell.PARTIAL_OUTPUT).doesNotContain("Exit Code");
    }

    @Test
    void testExecute_TaskOfAnotherRuntime_ReturnsNotFound() {
        AgentRuntimeId acme = AgentRuntimeId.fromName("ops", "acme");
        AgentRuntimeId globex = AgentRuntimeId.fromName("ops", "globex");
        ControllableShell shell = ControllableShell.cancellable();
        String taskId = backgroundManager.start(BackgroundBashOwner.of(acme, null, null), "cat secrets", shell,
                at.aimon.core.shell.ExecutionOptions.defaults()).getTaskId();
        shell.finish("acme only");

        ToolResult foreign = bashOutputTool.execute(ToolInput.of(Map.of("taskId", taskId)),
                ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, globex).build());
        ToolResult noRuntime = bashOutputTool.execute(ToolInput.of(Map.of("taskId", taskId)), context);
        ToolResult owner = bashOutputTool.execute(ToolInput.of(Map.of("taskId", taskId)),
                ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, acme).build());

        // The id is 32 random bits and the list is the whole application's: the owner check is the boundary.
        assertThat(foreign.isError()).isTrue();
        assertThat(foreign.getContent()).contains("Shell not found").doesNotContain("acme only");
        assertThat(noRuntime.isError()).isTrue();
        assertThat(owner.isSuccess()).isTrue();
        assertThat(owner.getContent()).contains("acme only");
    }

    @Test
    void testExecute_TaskOfAnotherSession_ReadsExactlyLikeAnUnknownId() {
        AgentRuntimeId acme = AgentRuntimeId.fromName("ops", "acme");
        SessionId sessionA = SessionId.of("session-a");
        ToolContext turnA = ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, acme)
                .put(ToolContextKeys.SESSION_ID, sessionA).build();
        ToolContext turnB = ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, acme)
                .put(ToolContextKeys.SESSION_ID, SessionId.of("session-b")).build();
        ToolContext forkOfA = ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, acme)
                .put(ToolContextKeys.EXECUTION_ID, ExecutionId.of("fork-1"))
                .put(ToolContextKeys.INVOKING_SESSION_ID, sessionA).build();
        ControllableShell shell = ControllableShell.cancellable();
        String taskId = backgroundManager.start(BackgroundBashOwner.of(turnA), "cat secrets", shell,
                at.aimon.core.shell.ExecutionOptions.defaults()).getTaskId();
        shell.finish("session a only");

        ToolResult otherSession = bashOutputTool.execute(ToolInput.of(Map.of("taskId", taskId)), turnB);
        ToolResult unknown = bashOutputTool.execute(ToolInput.of(Map.of("taskId", "bash_ffffffff")), turnB);
        ToolResult fork = bashOutputTool.execute(ToolInput.of(Map.of("taskId", taskId)), forkOfA);

        // Same runtime, other session: the answer must not differ from a task that never existed by a character
        // other than the id itself.
        assertThat(otherSession.isError()).isTrue();
        assertThat(otherSession.getContent()).isEqualTo(unknown.getContent().replace("bash_ffffffff", taskId));
        assertThat(fork.isSuccess()).as("a fork spawned for the session reads its task").isTrue();
        assertThat(fork.getContent()).contains("session a only");
    }

    @Test
    void testExecute_TaskOnAnotherNode_IsNotFoundForAnotherSession() {
        AgentRuntimeId acme = AgentRuntimeId.fromName("ops", "acme");
        ToolContext turnA = ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, acme)
                .put(ToolContextKeys.SESSION_ID, SessionId.of("session-a")).build();
        ToolContext turnB = ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, acme)
                .put(ToolContextKeys.SESSION_ID, SessionId.of("session-b")).build();
        InMemoryBackgroundBashStore shared = new InMemoryBackgroundBashStore();
        shared.putIfAbsent(BackgroundBashRecord.builder().taskId("bash_0000abcd").owner(BackgroundBashOwner.of(turnA))
                .nodeId("node-b").startedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(3600)).build());
        try (BackgroundBashManager nodeA = BackgroundBashManager.builder().store(shared).nodeId("node-a").build()) {
            BashOutputTool tool = new BashOutputTool(nodeA);

            ToolResult owner = tool.execute(ToolInput.of(Map.of("taskId", "bash_0000abcd")), turnA);
            ToolResult other = tool.execute(ToolInput.of(Map.of("taskId", "bash_0000abcd")), turnB);

            assertThat(owner.getContent()).contains("running on another node.");
            assertThat(other.getContent()).contains("Shell not found: bash_0000abcd").doesNotContain("another node");
        }
    }

    @Test
    void testExecute_TaskOnAnotherNode_ReportsWhereItRuns() {
        InMemoryBackgroundBashStore shared = new InMemoryBackgroundBashStore();
        shared.putIfAbsent(BackgroundBashRecord.builder().taskId("bash_0000eeee").nodeId("node-b")
                .startedAt(java.time.Instant.now()).expiresAt(java.time.Instant.now().plusSeconds(3600)).build());
        try (BackgroundBashManager nodeA = BackgroundBashManager.builder().store(shared).nodeId("node-a").build()) {
            ToolResult result = new BashOutputTool(nodeA).execute(ToolInput.of(Map.of("taskId", "bash_0000eeee")),
                    context);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("running on another node.").doesNotContain("node-b")
                    .contains("cannot be read from here").doesNotContain("Shell not found");
        }
    }

    @Test
    void testExecute_TaskThisNodeLost_SaysSo() {
        InMemoryBackgroundBashStore durable = new InMemoryBackgroundBashStore();
        durable.putIfAbsent(BackgroundBashRecord.builder().taskId("bash_0000ffff").nodeId("node-a")
                .startedAt(java.time.Instant.now()).expiresAt(java.time.Instant.now().plusSeconds(3600)).build());
        try (BackgroundBashManager restarted = BackgroundBashManager.builder().store(durable).nodeId("node-a")
                .build()) {
            ToolResult result = new BashOutputTool(restarted).execute(ToolInput.of(Map.of("taskId", "bash_0000ffff")),
                    context);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("before it restarted");
        }
    }

    @Test
    void testIntegration_WithBackgroundBashManager() throws InterruptedException {
        // Register task
        CompletableFuture<ShellCommandResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(100);
                return new ShellCommandResult(0, "Completed output", "", Duration.ofMillis(100));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        });
        backgroundManager.registerTask("task_123", "long command", future);

        // Check status while running
        ToolInput checkStatus = ToolInput.of("taskId", "task_123", "block", false);
        ToolResult statusResult = bashOutputTool.execute(checkStatus, context);
        assertThat(statusResult.isSuccess()).isTrue();

        // Wait for completion
        Thread.sleep(200);

        // Get final result
        ToolInput getFinal = ToolInput.of("taskId", "task_123", "block", true);
        ToolResult finalResult = bashOutputTool.execute(getFinal, context);

        assertThat(finalResult.isSuccess()).isTrue();
        assertThat(finalResult.getContent()).contains("Status: Completed");
    }

    /**
     * Builds an already-completed future carrying a shell result.
     *
     * <p>
     * No sleep is needed after registering one of these: {@code whenComplete} on an already-completed future runs its
     * handler on the calling thread, so the task's state is settled before {@code registerTask} returns.
     *
     * @param exitCode
     *            the exit code the command reported
     * @param stdout
     *            the captured standard output
     * @return the completed future
     */
    private static CompletableFuture<ShellCommandResult> completedWith(int exitCode, String stdout) {
        return CompletableFuture.completedFuture(new ShellCommandResult(exitCode, stdout, "", Duration.ofMillis(5)));
    }
}
