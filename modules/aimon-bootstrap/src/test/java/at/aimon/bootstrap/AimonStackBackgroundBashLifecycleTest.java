package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.bootstrap.runtime.AgentRuntimeLease;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.ExecutionEnvironmentSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Background {@code Bash} commands across the eviction of the tenant runtime that started them (EE-7, EE-13).
 *
 * <p>
 * A background command belongs to a process on this node, not to the runtime whose turn started it. A tenant runtime
 * is evicted when it goes idle and rebuilt on the next request, and the model that comes back still holds the task id
 * it was given. These rows pin what survives that: the command, the task list that knows it, and — for every other
 * tenant — everything.
 */
@DisabledOnOs(OS.WINDOWS)
class AimonStackBackgroundBashLifecycleTest {

    private static final Pattern TASK_ID = Pattern.compile("bash_[0-9a-f]{8}");

    private static final LlmClient STUB_LLM = new LlmClient() {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.text("done");
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    };

    private static AimonStackSpec.Builder ops(Path workspace) {
        return AimonStackSpec.builder().workspaceRoot(workspace.toString()).llm(LlmSpec.of(STUB_LLM))
                .agent(AgentSpec.of(AgentBundle.builder()
                        .agent(DefaultAgent.builder().name("ops").systemPrompt("You are ops.").maxIterations(5).build())
                        .build()));
    }

    private static AgentRuntimeId tenant(String name) {
        return AgentRuntimeId.fromName("ops", name);
    }

    @Test
    @DisplayName("a runtime rebuilt after eviction still finds, and can stop, the task its predecessor started")
    void evictedRuntimesTaskIsFoundByItsSuccessor(@TempDir Path workspace) throws Exception {
        final AgentRuntimeId acme = tenant("acme");
        try (AimonStack stack = AimonStackBuilder.build(ops(workspace).build())) {
            final String taskId;
            final long pid;
            try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(acme)) {
                taskId = startSleeper(lease.runtime());
                pid = awaitPid(workspace.resolve("ops/acme/pid"));
            }

            assertThat(stack.agentRuntimes().invalidate(acme)).isTrue();

            // Characterization (design §2-1): closing a local runtime's share does not stop the command. What used
            // to be lost was the task list, so the command ran on untracked until its ceiling.
            assertThat(alive(pid)).as("the command outlives the runtime that started it").isTrue();

            try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(acme)) {
                final ToolResult output = call(lease.runtime(), "BashOutput", Map.of("taskId", taskId, "block", false));
                assertThat(output.isSuccess()).as(output.getContent()).isTrue();
                assertThat(output.getContent()).contains("Status: Running");

                final ToolResult killed = call(lease.runtime(), "KillShell", Map.of("taskId", taskId));
                assertThat(killed.isSuccess()).as(killed.getContent()).isTrue();
                assertThat(killed.getContent()).contains("stopped");
                awaitDead(pid);

                assertThat(call(lease.runtime(), "BashOutput", Map.of("taskId", taskId)).getContent())
                        .contains("Status: Killed");
            } finally {
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    @DisplayName("evicting one tenant releases only its workspace: its command and the other tenant are untouched")
    void evictionReleasesOnlyThatRuntimesShare(@TempDir Path workspace) throws Exception {
        final AgentRuntimeId acme = tenant("acme");
        final AgentRuntimeId globex = tenant("globex");
        long acmePid = -1;
        long globexPid = -1;
        try (AimonStack stack = AimonStackBuilder.build(ops(workspace).build());
                AgentRuntimeLease globexLease = stack.agentRuntimes().acquire(globex)) {
            final String globexTask = startSleeper(globexLease.runtime());
            globexPid = awaitPid(workspace.resolve("ops/globex/pid"));
            final VirtualFileSystem acmeFiles;
            final String acmeTask;
            try (AgentRuntimeLease acmeLease = stack.agentRuntimes().acquire(acme)) {
                acmeTask = startSleeper(acmeLease.runtime());
                acmePid = awaitPid(workspace.resolve("ops/acme/pid"));
                acmeFiles = environmentOf(acmeLease.runtime()).fileSystem();
            }

            assertThat(stack.agentRuntimes().invalidate(acme)).isTrue();

            // Acme's share is gone, and nothing else: its command keeps running (nobody asked for it to stop), and
            // globex — on the same provider — still has a shell, a workspace and its task.
            assertThat(acmeFiles.getStatus().isAvailable()).as("the evicted tenant's workspace is released").isFalse();
            assertThat(alive(acmePid)).isTrue();
            assertThat(alive(globexPid)).isTrue();
            assertThat(environmentOf(globexLease.runtime()).fileSystem().getStatus().isAvailable()).isTrue();
            final ToolResult echo = call(globexLease.runtime(), "Bash", Map.of("command", "echo still-here"));
            assertThat(echo.isSuccess()).as(echo.getContent()).isTrue();
            assertThat(echo.getContent()).contains("still-here");
            assertThat(call(globexLease.runtime(), "BashOutput", Map.of("taskId", globexTask, "block", false))
                    .getContent()).contains("Status: Running");

            // One list for the stack is not one list for every tenant: globex cannot read or stop acme's task.
            final ToolResult foreignRead = call(globexLease.runtime(), "BashOutput", Map.of("taskId", acmeTask));
            assertThat(foreignRead.isError()).isTrue();
            assertThat(foreignRead.getContent()).contains("Shell not found");
            final ToolResult foreignKill = call(globexLease.runtime(), "KillShell", Map.of("taskId", acmeTask));
            assertThat(foreignKill.isError()).isTrue();
            assertThat(foreignKill.getContent()).contains("Shell not found");
            assertThat(alive(acmePid)).isTrue();
        } finally {
            ProcessHandle.of(acmePid).ifPresent(ProcessHandle::destroyForcibly);
            ProcessHandle.of(globexPid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    @DisplayName("an invalidated runtime closing late does not take the workspace from its successor")
    void overlappingRuntimesOfOneIdShareTheSlot(@TempDir Path workspace) {
        final AgentRuntimeId acme = tenant("acme");
        try (AimonStack stack = AimonStackBuilder.build(ops(workspace).build())) {
            final AgentRuntimeLease old = stack.agentRuntimes().acquire(acme);
            // Still held, so it is only unregistered; the next request builds a second runtime of the same id.
            assertThat(stack.agentRuntimes().invalidate(acme)).isTrue();

            try (AgentRuntimeLease fresh = stack.agentRuntimes().acquire(acme)) {
                assertThat(fresh.runtime()).isNotSameAs(old.runtime());

                // The last holder lets go: the old runtime closes now, and with it its binding — not the slot.
                old.close();

                assertThat(environmentOf(fresh.runtime()).fileSystem().getStatus().isAvailable())
                        .as("the successor's workspace survives its predecessor's close").isTrue();
                final ToolResult echo = call(fresh.runtime(), "Bash", Map.of("command", "echo still-here"));
                assertThat(echo.isSuccess()).as(echo.getContent()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("closing the stack stops a tenant's running background command")
    void closingTheStackStopsRunningCommands(@TempDir Path workspace) throws Exception {
        final AgentRuntimeId acme = tenant("acme");
        long pid = -1;
        try {
            final AimonStack stack = AimonStackBuilder.build(ops(workspace).build());
            try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(acme)) {
                startSleeper(lease.runtime());
                pid = awaitPid(workspace.resolve("ops/acme/pid"));
            }

            // The tenant's binding closes with its runtime, in AGENT_RUNTIMES — one phase before the commands are
            // stopped. That order is safe only because closing a binding leaves running commands alone.
            stack.close();

            awaitDead(pid);
        } finally {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    @DisplayName("the stack's background ceiling reaches the environment the tools run in")
    void backgroundCeilingReachesTheEnvironment(@TempDir Path workspace) {
        final AimonStackSpec spec = ops(workspace).executionEnvironment(ExecutionEnvironmentSpec.builder()
                .contentSearch(false).backgroundCommandTimeout(Duration.ofMinutes(30)).build()).build();
        try (AimonStack stack = AimonStackBuilder.build(spec);
                AgentRuntimeLease lease = stack.agentRuntimes().acquire(tenant("acme"))) {
            assertThat(environmentOf(lease.runtime()).backgroundCommandTimeout()).contains(Duration.ofMinutes(30));

            final ToolResult started = call(lease.runtime(), "Bash",
                    Map.of("command", "true", "run_in_background", true));
            assertThat(started.getContent()).contains("The environment stops it after 30 minutes");
        }
    }

    /** Starts {@code sleep} in the background, leaving its shell's pid in {@code pid} under the workspace. */
    private static String startSleeper(AgentRuntime runtime) {
        final ToolResult started = call(runtime, "Bash",
                Map.of("command", "echo $$ > pid; exec sleep 120", "run_in_background", true));
        assertThat(started.isSuccess()).as(started.getContent()).isTrue();
        final Matcher matcher = TASK_ID.matcher(started.getContent());
        assertThat(matcher.find()).as(started.getContent()).isTrue();
        return matcher.group();
    }

    private static long awaitPid(Path pidFile) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                final String text = Files.readString(pidFile).trim();
                if (!text.isEmpty()) {
                    return Long.parseLong(text);
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the background command never wrote " + pidFile);
    }

    private static void awaitDead(long pid) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (alive(pid)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("process " + pid + " is still alive");
            }
            Thread.sleep(20);
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Calls a tool the way an execution of this runtime would: its environment and its runtime id in the context. */
    private static ToolResult call(AgentRuntime runtime, String toolName, Map<String, Object> input) {
        final Tool tool = runtime.getAvailableTools().stream()
                .filter(candidate -> candidate.getDefinition().getName().equals(toolName)).findFirst().orElseThrow();
        final ToolContext context = ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ENVIRONMENT, environmentOf(runtime))
                .put(ToolContextKeys.AGENT_RUNTIME_ID, runtime.getId()).build();
        return tool.execute(ToolInput.of(input), context);
    }

    private static ExecutionEnvironment environmentOf(AgentRuntime runtime) {
        return ((OrcaAgentRuntime) runtime).getExecutionEnvironmentProvider().resolve(
                EnvironmentRequest.builder().agentRuntimeId(runtime.getId()).agent(runtime.getAgent()).build());
    }
}
