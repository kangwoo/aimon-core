package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.DefaultLiveSession;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.DeclarativePreToolHook;
import at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.workflow.AgentTask;
import at.aimon.core.workflow.WorkflowRunner;

/**
 * L2 — {@code hooks.json} commands run in the execution environment of the execution they are about, and on the host
 * only when there is no execution (aimon-core #273: EE-48, EE-52).
 *
 * <p>
 * Every node here loads {@code hooks.json} through the real {@code HookHotReloadBootstrap}, with the host-placed
 * commands on a {@code LocalShell} rooted at {@link #hostDir} &mdash; a directory that is not the workspace &mdash; so
 * where a command ran is read off the files it writes ({@code pwd > marker}). No sandbox is involved: a second local
 * workspace stands in for an environment that is not the host's, and {@link SessionStateShell} for a shell whose
 * {@code cd}/{@code export} persist between the model's commands.
 */
@DisplayName("RT-IT-L2: hooks.json commands run in the execution's environment")
@DisabledOnOs(OS.WINDOWS)
class HooksJsonExecutionEnvironmentIntegrationTest {

    private static final String WORKER = "it-worker";
    private static final String TODO = "GUARDED-TODO-4b17";
    private static final String DENIED = "DENIED-BY-POLICY-77e2";

    @TempDir
    Path tempDir;

    private Path hostDir;
    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;

    @BeforeEach
    void setUp() throws IOException {
        hostDir = Files.createDirectories(tempDir.resolve("host"));
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient().globalFallback(ScriptedLlmClient.text("done"));
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private static InMemorySubagentRegistry worker() {
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        registry.register(Subagent.builder().name(WORKER).description("Integration-test worker subagent")
                .systemPrompt("You are the integration-test worker.").maxIterations(6).build());
        return registry;
    }

    private OrcaRuntimeItSupport.Node node(String name, String hooksJson) {
        return node(name, hooksJson, UnaryOperator.identity(), hostDir);
    }

    private OrcaRuntimeItSupport.Node node(String name, String hooksJson,
            UnaryOperator<ExecutionEnvironmentProvider> environments, Path host) {
        return support.newNode(name, llm, OrcaRuntimeItSupport.options().codeSubagents(worker())
                .environmentProvider(environments).workflowRunnerEnabled(true).hooksJson(hooksJson, host));
    }

    private static String preTool(String handler) {
        return "{\"hooks\":{\"preTool\":[{\"matcher\":\"TodoWrite\",\"hooks\":[" + handler + "]}]}}";
    }

    private static String command(String command) {
        return "{\"type\":\"command\",\"command\":" + json(command) + "}";
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Map<String, Object> todoInput() {
        return Map.of("todos", List.of(Map.of("content", TODO, "status", "in_progress", "activeForm", "Doing it")));
    }

    /** One turn: the model calls TodoWrite once. Returns what the model saw back. */
    private List<String> runTodo(OrcaRuntimeItSupport.Node node) {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("TodoWrite", todoInput()),
                ScriptedLlmClient.text("finished"));
        final OrcaAgentExecutionResult result = node.run(sessionId, "write the todo");
        assertThat(result.isSuccess()).isTrue();
        return llm.lastCallFor(sessionId.value()).observations();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new AssertionError("expected " + file, e);
        }
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void awaitFile(Path file) throws InterruptedException {
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (!Files.exists(file) && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        assertThat(file).exists();
    }

    // --- test 1: preTool in the execution's environment ------------------------------------------------------------

    @Test
    @DisplayName("a preTool command with no \"shell\" runs in the execution's environment, not the host shell")
    void preToolRunsInTheEnvironment() {
        final OrcaRuntimeItSupport.Node node = node("agent-pretool", preTool(command("pwd > where.txt; exit 0")));

        final List<String> seen = runTodo(node);

        assertThat(seen).anyMatch(observation -> observation.contains("Todo list updated"));
        assertThat(node.root().resolve("where.txt")).exists();
        assertThat(Path.of(read(node.root().resolve("where.txt")))).isEqualTo(real(node.root()));
        assertThat(hostDir.resolve("where.txt")).doesNotExist();
    }

    @Test
    @DisplayName("an environment-placed preTool command that exits 2 denies the tool")
    void preToolInTheEnvironmentDenies() {
        final OrcaRuntimeItSupport.Node node = node("agent-pretool-deny",
                preTool(command("echo " + DENIED + " >&2; exit 2")));

        final List<String> seen = runTodo(node);

        assertThat(seen).anyMatch(observation -> observation.contains(DENIED));
        assertThat(seen).noneMatch(observation -> observation.contains("Todo list updated"));
    }

    // --- test 2: the agent's shell state does not reach the hook ---------------------------------------------------

    @Test
    @DisplayName("the agent's export and cd do not reach a hooks.json command; a skill hook (the control) sees both")
    void agentShellStateDoesNotReachTheHook() {
        final Path policySaw = tempDir.resolve("policy-saw.txt");
        final Path skillHookSaw = tempDir.resolve("skill-hook-saw.txt");
        final OrcaRuntimeItSupport.Node node = node("agent-isolated",
                preTool(command("echo \"[$AGENT_SECRET] $(pwd)\" > '" + policySaw + "'")),
                SessionStateShell.wrapping(tempDir.resolve("state"), true), hostDir);
        // The control: built as SkillHookSetParser builds a skill's hook. It is not isolated, so it starts from the
        // session's state as a sandbox hook command does, which is what proves the fixture is not vacuous.
        node.hookRegistry().register(HookEventType.PRE_TOOL, new DeclarativePreToolHook("control-skill",
                NameOnlyPredicate.of("TodoWrite"),
                new ShellAction("echo \"[$AGENT_SECRET] $(pwd)\" > '" + skillHookSaw + "'", Duration.ofSeconds(10)),
                new DefaultShellActionExecutor()));
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("Bash", Map.of("command", "export AGENT_SECRET=leak; mkdir -p sub; cd sub")),
                ScriptedLlmClient.callTool("TodoWrite", todoInput()), ScriptedLlmClient.text("finished"));

        assertThat(node.run(sessionId, "steer the policy").isSuccess()).isTrue();

        final String root = real(node.root()).toString();
        assertThat(read(policySaw)).isEqualTo("[] " + root);
        assertThat(read(skillHookSaw)).isEqualTo("[leak] " + root + "/sub");
    }

    @Test
    @DisplayName("a shell that cannot isolate does not run a hooks.json guard: the tool is blocked")
    void aShellWithoutIsolationBlocksTheGuard() {
        final Path ran = tempDir.resolve("ran.txt");
        final OrcaRuntimeItSupport.Node node = node("agent-no-isolation", preTool(command("touch '" + ran + "'")),
                SessionStateShell.wrapping(tempDir.resolve("state-plain"), false), hostDir);

        final List<String> seen = runTodo(node);

        assertThat(seen).anyMatch(observation -> observation.contains("cannot run an isolated command"));
        assertThat(seen).noneMatch(observation -> observation.contains("Todo list updated"));
        assertThat(ran).doesNotExist();
    }

    // --- test 3: "shell": "host" -----------------------------------------------------------------------------------

    @Test
    @DisplayName("\"shell\": \"host\" keeps a preTool command on the host shell")
    void hostOptOut() {
        final OrcaRuntimeItSupport.Node node = node("agent-host",
                preTool("{\"type\":\"command\",\"command\":\"pwd > where.txt\",\"shell\":\"host\"}"));

        runTodo(node);

        assertThat(hostDir.resolve("where.txt")).exists();
        assertThat(node.root().resolve("where.txt")).doesNotExist();
    }

    // --- test 4: out-of-execution events ---------------------------------------------------------------------------

    @Test
    @DisplayName("onSessionStart, onSessionEnd and onConfigReload run on the host shell")
    void outOfExecutionEventsStayOnTheHost() throws Exception {
        final String hooks = "{\"hooks\":{" + "\"onSessionStart\":[{\"hooks\":[" + command("pwd > session-start.txt")
                + "]}]," + "\"onSessionEnd\":[{\"hooks\":[" + command("pwd > session-end.txt") + "]}],"
                + "\"onConfigReload\":[{\"hooks\":[" + command("pwd > reload.txt") + "]}]}}";
        final OrcaRuntimeItSupport.Node node = node("agent-lifecycle", hooks);

        final DefaultLiveSession session = node.openLiveSession(OrcaRuntimeItSupport.newSession());
        session.close();
        node.rewriteHooksJson(hooks + "\n");
        awaitFile(hostDir.resolve("reload.txt"));

        for (String marker : List.of("session-start.txt", "session-end.txt", "reload.txt")) {
            assertThat(hostDir.resolve(marker)).as(marker).exists();
            assertThat(Path.of(read(hostDir.resolve(marker)))).as(marker).isEqualTo(real(hostDir));
            assertThat(node.root().resolve(marker)).as(marker).doesNotExist();
        }
    }

    // --- test 5: subagentStop in the fork's environment ------------------------------------------------------------

    @Test
    @DisplayName("subagentStop runs in the fork's environment; subagentStart in the spawner's")
    void subagentStopSeesTheForksEnvironment() throws Exception {
        final Path elsewhere = Files.createDirectories(tempDir.resolve("fork-workspace"));
        final String hooks = "{\"hooks\":{" + "\"subagentStart\":[{\"hooks\":[" + command("pwd > start.txt") + "]}],"
                + "\"subagentStop\":[{\"hooks\":[" + command("pwd > stop.txt") + "]}]}}";
        try (LocalExecutionEnvironmentProvider forkEnvironments = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(elsewhere).contentSearch(false).build()) {
            final OrcaRuntimeItSupport.Node node = node("agent-subagent", hooks, placingTheForkIn(forkEnvironments),
                    hostDir);
            final SessionId sessionId = OrcaRuntimeItSupport.newSession();
            llm.script(sessionId.value(),
                    ScriptedLlmClient.callTool("Task",
                            Map.of("subagent_name", WORKER, "prompt", "work", "description", "delegate")),
                    ScriptedLlmClient.text("finished"));

            assertThat(node.run(sessionId, "delegate").isSuccess()).isTrue();

            assertThat(Path.of(read(elsewhere.resolve("stop.txt")))).isEqualTo(real(elsewhere));
            assertThat(Path.of(read(node.root().resolve("start.txt")))).isEqualTo(real(node.root()));
            assertThat(node.root().resolve("stop.txt")).doesNotExist();
            assertThat(hostDir.resolve("start.txt")).doesNotExist();
            assertThat(hostDir.resolve("stop.txt")).doesNotExist();
        }
    }

    /** Places the {@link #WORKER} fork in another provider's environment, as a sandbox provider would. */
    private static UnaryOperator<ExecutionEnvironmentProvider> placingTheForkIn(
            ExecutionEnvironmentProvider forkEnvironments) {
        return local -> request -> {
            final boolean worker = request.fork().map(fork -> WORKER.equals(fork.name())).orElse(false);
            return worker
                    ? forkEnvironments
                            .resolve(EnvironmentRequest.builder().agentRuntimeId(request.agentRuntimeId()).build())
                    : local.resolve(request);
        };
    }

    // --- test 7: the runtime-level runner --------------------------------------------------------------------------

    @Test
    @DisplayName("from the runtime-level runner, subagentStart has no execution (host) and subagentStop has the fork's")
    void runtimeRunnerSubagentHooksFollowTheOneRule() {
        final String hooks = "{\"hooks\":{" + "\"subagentStart\":[{\"hooks\":[" + command("pwd > start.txt") + ","
                + "{\"type\":\"command\",\"command\":\"pwd > start-env.txt\",\"shell\":\"environment\"}]}],"
                + "\"subagentStop\":[{\"hooks\":[" + command("pwd > stop.txt") + ","
                + "{\"type\":\"command\",\"command\":\"pwd > stop-env.txt\",\"shell\":\"environment\"}]}]}}";
        final OrcaRuntimeItSupport.Node node = node("agent-runner", hooks);
        final WorkflowRunner runner = node.runtime().getWorkflowRunner().orElseThrow();
        final Subagent workerSubagent = worker().getSubagent(WORKER).orElseThrow();

        runner.run(ctx -> ctx.agent(AgentTask.of(workerSubagent, "go")).text());

        // No calling execution and no fork environment yet: the host.
        assertThat(Path.of(read(hostDir.resolve("start.txt")))).isEqualTo(real(hostDir));
        // Explicit "environment" with no execution behind the firing: not run.
        assertThat(hostDir.resolve("start-env.txt")).doesNotExist();
        assertThat(node.root().resolve("start-env.txt")).doesNotExist();
        // The fork ran its loop in an environment of its own (the local provider's root): its stop runs there.
        assertThat(Path.of(read(node.root().resolve("stop.txt")))).isEqualTo(real(node.root()));
        assertThat(Path.of(read(node.root().resolve("stop-env.txt")))).isEqualTo(real(node.root()));
        assertThat(hostDir.resolve("stop.txt")).doesNotExist();
    }

    // --- test 8: an isolated workflow branch under the local provider ----------------------------------------------

    @Test
    @DisplayName("a hooks.json guard in an isolated branch runs at the workspace root, where its relative script is")
    void isolatedBranchRunsAtTheWorkspaceRoot() throws Exception {
        support.seedNodeFile("agent-branch", ".aimon/hooks/guard.sh", "#!/bin/bash\npwd >> audit.log\nexit 0\n");
        final OrcaRuntimeItSupport.Node node = node("agent-branch", preTool(command("bash .aimon/hooks/guard.sh")));
        final WorkflowRunner runner = node.runtime().getWorkflowRunner().orElseThrow();
        final Subagent workerSubagent = worker().getSubagent(WORKER).orElseThrow();
        // The Bash write proves the step ran in a branch: under the local provider it lands in .worktrees/{key}/.
        llm.script(ScriptedLlmClient.UNROUTED,
                ScriptedLlmClient.callTool("Bash", Map.of("command", "echo branch > branch-proof.txt")),
                ScriptedLlmClient.callTool("TodoWrite", todoInput()), ScriptedLlmClient.text("branch done"));

        runner.run(
                ctx -> ctx.agent(AgentTask.builder().subagent(workerSubagent).goal("go").isolate(true).build()).text());

        // The guard ran (no 127, which would have blocked the tool) and the tool after it.
        assertThat(llm.lastCallFor(ScriptedLlmClient.UNROUTED).observations())
                .anyMatch(observation -> observation.contains("Todo list updated"));
        assertThat(Path.of(read(node.root().resolve("audit.log")))).isEqualTo(real(node.root()));
        // The step did run in a branch, and nothing the hook wrote sits there, so a merge has nothing of the hook's to
        // promote.
        try (Stream<Path> files = Files.walk(node.root().resolve(".worktrees"))) {
            final List<String> names = files.map(file -> file.getFileName().toString()).toList();
            assertThat(names).contains("branch-proof.txt").doesNotContain("audit.log");
        }
        assertThat(node.root().resolve("branch-proof.txt")).doesNotExist();
    }

    // --- test 9: one working directory for both placements ---------------------------------------------------------

    @Test
    @DisplayName("with the host shell on the workspace root, both placements run in that one directory")
    void bothPlacementsShareTheWorkspaceRoot() {
        final String name = "agent-one-cwd";
        final Path workspace = tempDir.resolve(name);
        final String hooks = "{\"hooks\":{" + "\"preTool\":[{\"matcher\":\"TodoWrite\",\"hooks\":["
                + command("pwd > a.txt") + "]}]," + "\"onSessionStart\":[{\"hooks\":[" + command("pwd > b.txt")
                + "]}]}}";
        final OrcaRuntimeItSupport.Node node = node(name, hooks, UnaryOperator.identity(), workspace);

        node.openLiveSession(OrcaRuntimeItSupport.newSession());
        runTodo(node);

        assertThat(real(workspace).toString()).isNotEqualTo(real(Path.of(System.getProperty("user.dir"))).toString());
        assertThat(Path.of(read(workspace.resolve("a.txt")))).isEqualTo(real(workspace));
        assertThat(Path.of(read(workspace.resolve("b.txt")))).isEqualTo(real(workspace));
    }

    // --- test 10: AIMON_HOOK_DIR ------------------------------------------------------------------------------------

    private static final String SCRIPT = "#!/bin/bash\necho \"HOOK-SCRIPT-91aa self=$0\" >&2\nexit 2\n";

    private static String selfReportedIn(List<String> observations) {
        final String veto = observations.stream().filter(observation -> observation.contains("HOOK-SCRIPT-91aa"))
                .findFirst().orElseThrow(() -> new AssertionError("the script never spoke: " + observations));
        return veto.substring(veto.indexOf("self=") + "self=".length()).strip();
    }

    @Test
    @DisplayName("AIMON_HOOK_DIR under default placement is a run directory outside the workspace, gone after the run")
    void hookDirIsARunDirectoryOutsideTheWorkspace() {
        support.seedNodeFile("agent-hook-dir", ".aimon/hooks/guard.sh", SCRIPT);
        final OrcaRuntimeItSupport.Node node = node("agent-hook-dir",
                preTool(command("bash \"$AIMON_HOOK_DIR/guard.sh\"")));

        final List<String> seen = runTodo(node);

        // Denied by the script itself, not by a 127.
        assertThat(seen).noneMatch(observation -> observation.contains("Todo list updated"));
        // EE-109: the bytes were handed to the shell for this run; no copy was staged where the agent can write.
        final Path self = Path.of(selfReportedIn(seen));
        assertThat(self.getFileName()).hasToString("guard.sh");
        assertThat(self.getParent().getFileName().toString()).startsWith("aimon-run-");
        assertThat(self.toAbsolutePath().startsWith(real(node.root()))).isFalse();
        assertThat(self.getParent()).doesNotExist();
        assertThat(node.root().resolve(LocalExecutionEnvironmentProvider.DEFAULT_STAGING_ROOT).resolve("hooks-project"))
                .doesNotExist();
    }

    @Test
    @DisplayName("AIMON_HOOK_DIR on the host is the layer's own hooks/ directory")
    void hookDirOnTheHostIsTheHostPath() {
        support.seedNodeFile("agent-hook-dir-host", ".aimon/hooks/guard.sh", SCRIPT);
        final OrcaRuntimeItSupport.Node node = node("agent-hook-dir-host", preTool(
                "{\"type\":\"command\",\"command\":\"bash \\\"$AIMON_HOOK_DIR/guard.sh\\\"\",\"shell\":\"host\"}"));

        final String self = selfReportedIn(runTodo(node));

        assertThat(self).isEqualTo(node.root().resolve(".aimon/hooks/guard.sh").toAbsolutePath().toString());
    }

    @Test
    @DisplayName("AIMON_HOOK_DIR follows the route: a runtime runner's subagentStart gets the host path")
    void hookDirFollowsTheRouteToTheHost() {
        support.seedNodeFile("agent-hook-dir-runner", ".aimon/hooks/start.sh",
                "#!/bin/bash\necho \"$0\" > \"" + hostDir.resolve("start-self.txt") + "\"\n");
        final OrcaRuntimeItSupport.Node node = node("agent-hook-dir-runner",
                "{\"hooks\":{\"subagentStart\":[{\"hooks\":[" + command("bash \"$AIMON_HOOK_DIR/start.sh\"") + "]}]}}");
        final Subagent workerSubagent = worker().getSubagent(WORKER).orElseThrow();

        node.runtime().getWorkflowRunner().orElseThrow()
                .run(ctx -> ctx.agent(AgentTask.of(workerSubagent, "go")).text());

        assertThat(read(hostDir.resolve("start-self.txt")))
                .isEqualTo(node.root().resolve(".aimon/hooks/start.sh").toAbsolutePath().toString());
    }
}
