package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;

/**
 * L2 — a skill's shell hook reaches the scripts in its own skill directory through {@code $AIMON_SKILL_DIR} (EE-50).
 *
 * <p>
 * The hook command runs in the shell of the execution that fired it, with the workspace as its working directory, so
 * a path relative to the skill does not resolve and a host path may not exist. Every test here declares a
 * {@code preTool} guard whose whole command is {@code bash "$AIMON_SKILL_DIR/scripts/guard.sh"} and lets the real
 * assembly — skill registry, {@code Skill} tool, fork, hook dispatch, local environment — decide whether the script
 * is found. A guard that is not found exits 127, which reads as "allow": the tool it was meant to stop runs.
 */
@DisplayName("RT-IT-L2: a skill's shell hook finds its own scripts through $AIMON_SKILL_DIR")
@DisabledOnOs(OS.WINDOWS)
class SkillHookSkillDirIntegrationTest {

    private static final String WORKER = "it-worker";
    private static final String SKILL = "it-scripted";
    private static final String GUARD_SAYS = "GUARD-SCRIPT-5d3c";
    private static final String TODO = "GUARDED-TODO-9a41";

    /** The guard reports where it was run from, then vetoes. Not executable on purpose: the hook runs it with bash. */
    private static final String GUARD_SCRIPT = "#!/bin/bash\necho \"" + GUARD_SAYS
            + " dir=$AIMON_SKILL_DIR self=$0\" >&2\nexit 2\n";

    private static final String HOOKS = "  preTool:\n    - matcher: TodoWrite\n"
            + "      action: { type: shell, command: 'bash \"$AIMON_SKILL_DIR/scripts/guard.sh\"' }\n";

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private OrcaRuntimeItSupport.Node node(String name, String hooksYaml,
            UnaryOperator<ExecutionEnvironmentProvider> environments) {
        support.seedSkill(name, SKILL,
                "---\nname: " + SKILL + "\ndescription: Runs in a fork guarded by one of its own scripts\n"
                        + "execution:\n  mode: fork\n  agent: " + WORKER + "\nhooks:\n" + hooksYaml
                        + "---\n\nDo the guarded work.\n");
        support.seedSkillFile(name, SKILL, "scripts/guard.sh", GUARD_SCRIPT);
        final InMemorySubagentRegistry codeSubagents = new InMemorySubagentRegistry();
        codeSubagents.register(Subagent.builder().name(WORKER).description("Integration-test worker subagent")
                .systemPrompt("You are the integration-test worker.").maxIterations(6).build());
        return support.newNode(name, llm, OrcaRuntimeItSupport.options().codeSubagents(codeSubagents)
                .skillShellHooks(true).environmentProvider(environments));
    }

    /**
     * A provider that places the {@link #WORKER} fork in another provider's environment, the way a sandbox provider
     * places a fork in a container of its own. The parent is dropped from the request: the local provider answers a
     * request that names a parent with that parent.
     */
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

    /** Runs the skill; its fork calls TodoWrite once. Returns what the fork's model saw. */
    private List<String> runSkill(OrcaRuntimeItSupport.Node node) {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), WORKER);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Skill", Map.of("skill", SKILL)),
                ScriptedLlmClient.text("done"));
        llm.script(forkRoute, ScriptedLlmClient.callTool("TodoWrite",
                Map.of("todos",
                        List.of(Map.of("content", TODO, "status", "in_progress", "activeForm", "Doing " + TODO)))),
                ScriptedLlmClient.text("fork finished"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "run the scripted skill");

        assertThat(result.isSuccess()).isTrue();
        return llm.lastCallFor(forkRoute).observations();
    }

    private static String stagedDirReportedIn(List<String> observations) {
        final String veto = observations.stream().filter(observation -> observation.contains(GUARD_SAYS)).findFirst()
                .orElseThrow(() -> new AssertionError("the guard script never spoke: " + observations));
        final int from = veto.indexOf("dir=") + "dir=".length();
        return veto.substring(from, veto.indexOf(" self=", from));
    }

    @Test
    @DisplayName("the guard script is found and its veto stops the tool")
    void guardScriptIsFoundThroughSkillDir() {
        final List<String> seenByFork = runSkill(node("agent-skill-dir", HOOKS, UnaryOperator.identity()));

        // The script ran: its stderr is the veto the model reads. Without the variable the command is
        // `bash "/scripts/guard.sh"`, exit 127, which allows the call.
        assertThat(seenByFork).anyMatch(observation -> observation.contains(GUARD_SAYS));
        assertThat(seenByFork)
                .noneMatch(observation -> observation.contains("Todo list updated") || observation.contains(TODO));

        // And it ran from a staged copy: a directory that holds the script, outside the control store the shell
        // is not meant to read.
        final String stagedDir = stagedDirReportedIn(seenByFork);
        assertThat(Path.of(stagedDir)).isAbsolute();
        assertThat(Path.of(stagedDir, "scripts", "guard.sh")).exists();
        assertThat(stagedDir).contains(LocalExecutionEnvironmentProvider.DEFAULT_STAGING_ROOT + "/" + SKILL + "/")
                .doesNotContain("/" + LocalExecutionEnvironmentProvider.CONTROL_DIRECTORY + "/");
    }

    @Test
    @DisplayName("a fork placed in another environment gets the copy staged into that environment")
    void forkInAnotherEnvironmentGetsItsOwnStagedCopy() throws Exception {
        final Path elsewhere = Files.createDirectories(tempDir.resolve("fork-workspace"));
        try (LocalExecutionEnvironmentProvider forkEnvironments = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(elsewhere).contentSearch(false).build()) {
            final OrcaRuntimeItSupport.Node node = node("agent-fork-elsewhere", HOOKS,
                    placingTheForkIn(forkEnvironments));

            final List<String> seenByFork = runSkill(node);

            // The Skill tool staged the skill where the invoking turn runs. The hook fires in the fork, whose shell
            // sees a different workspace: the path it is given has to exist there, not on the spawning side.
            final String stagedDir = stagedDirReportedIn(seenByFork);
            assertThat(Path.of(stagedDir).toRealPath()).startsWith(elsewhere.toRealPath());
            assertThat(Path.of(stagedDir, "scripts", "guard.sh")).exists();
            assertThat(seenByFork)
                    .noneMatch(observation -> observation.contains("Todo list updated") || observation.contains(TODO));
        }
    }

    @Test
    @DisplayName("a guard whose skill cannot be staged where the hook fires blocks the tool, and says why")
    void guardBlocksWhenTheSkillCannotBeStaged() throws Exception {
        final Path elsewhere = Files.createDirectories(tempDir.resolve("tight-workspace"));
        // One byte: nothing can be staged into the fork's environment, while the invoking turn's keeps the default.
        try (LocalExecutionEnvironmentProvider forkEnvironments = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(elsewhere).maxStagedBytes(1).contentSearch(false).build()) {
            final OrcaRuntimeItSupport.Node node = node("agent-fork-tight", HOOKS, placingTheForkIn(forkEnvironments));

            final List<String> seenByFork = runSkill(node);

            assertThat(seenByFork).anyMatch(observation -> observation.contains("guard hook '" + SKILL + "' (preTool)")
                    && observation.contains("skill directory could not be staged")
                    && observation.contains("fail-closed"));
            // Neither the script (there is no copy to run) nor the tool it guards ran.
            assertThat(seenByFork).noneMatch(observation -> observation.contains(GUARD_SAYS)
                    || observation.contains("Todo list updated") || observation.contains(TODO));
        }
    }

    @Test
    @DisplayName("the same hook declared failOpen lets the tool run when the skill cannot be staged, without running the command")
    void failOpenHookLetsTheToolRunWhenTheSkillCannotBeStaged() throws Exception {
        final Path elsewhere = Files.createDirectories(tempDir.resolve("tight-workspace-open"));
        final Path marker = tempDir.resolve("command-ran");
        // The command would leave a marker even with the variable unset. It must not run at all: a command written
        // against "$AIMON_SKILL_DIR/..." would otherwise run against "/...".
        final String hooks = "  preTool:\n    - matcher: TodoWrite\n      action: { type: shell, command: 'touch "
                + marker + "; bash \"$AIMON_SKILL_DIR/scripts/guard.sh\"' }\n      failOpen: true\n";
        try (LocalExecutionEnvironmentProvider forkEnvironments = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(elsewhere).maxStagedBytes(1).contentSearch(false).build()) {
            final OrcaRuntimeItSupport.Node node = node("agent-fork-tight-open", hooks,
                    placingTheForkIn(forkEnvironments));

            final List<String> seenByFork = runSkill(node);

            assertThat(seenByFork).anyMatch(observation -> observation.contains("Todo list updated"));
            assertThat(marker).doesNotExist();
        }
    }
}
