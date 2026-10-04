package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.DeclarativeHookOptions;
import at.aimon.core.skill.hook.declarative.DeclarativePreToolHook;
import at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;

/**
 * L2 — the three boundaries one runtime keeps between the executions that share it (EE-49, EE-51, EE-58).
 *
 * <p>
 * Every test here runs two things against <b>one</b> assembled runtime — two sessions, or a session and its fork —
 * because that is the only arrangement in which these boundaries can be crossed. An agent-scoped registry or task list
 * is correct by accident as long as one session uses it.
 *
 * <ul>
 * <li><b>Skill hooks</b> reach the skill's fork and that fork's descendants, and nothing else: not another session,
 * and not the session that invoked the skill.
 * <li><b>A guard whose command cannot run</b> blocks the tool, and says why.
 * <li><b>Background commands</b> belong to the session they were started for: its turns and its forks, and no other
 * session of the runtime.
 * </ul>
 */
@DisplayName("RT-IT-L2: skill hooks, unrunnable guards and background commands stay inside their execution")
class IsolationBoundaryIntegrationTest {

    private static final String NODE = "agent-a";
    private static final String WORKER = "it-worker";
    private static final String HELPER = "it-helper";
    private static final String GUARDED_SKILL = "it-guarded";
    private static final String GUARD_REASON = "SKILL-GUARD-7e21";
    private static final String NOTE = "note.txt";
    private static final String SHELL_GUARDED_SKILL = "it-shell-guarded";
    private static final String PROVIDER_DOWN = "PROVIDER-DOWN-4b1e";

    /** Matches the id in {@code "Background task started with ID: bash_1a2b3c4d"}, wherever it is quoted. */
    private static final Pattern TASK_ID = Pattern.compile("bash_[0-9a-f]{8}");

    private static final int GATE_TIMEOUT_SECONDS = 30;

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();

        // A fork-mode skill whose only hook refuses Bash. A deny action rather than a shell guard: the default
        // assembly's skill parser has no shell executor, and where the hook fires is what is under test here.
        support.seedSkill(NODE, GUARDED_SKILL,
                "---\nname: " + GUARDED_SKILL + "\ndescription: Runs in a fork that may not use Bash\n"
                        + "execution:\n  mode: fork\n  agent: " + WORKER + "\nhooks:\n  preTool:\n"
                        + "    - matcher: Bash\n      action: { type: deny, reason: \"" + GUARD_REASON + "\" }\n"
                        + "---\n\nDo the guarded work.\n");

        final InMemorySubagentRegistry codeSubagents = new InMemorySubagentRegistry();
        codeSubagents.register(Subagent.builder().name(WORKER).description("Integration-test worker subagent")
                .systemPrompt("You are the integration-test worker.").maxIterations(6).build());
        codeSubagents.register(Subagent.builder().name(HELPER).description("Integration-test helper subagent")
                .systemPrompt("You are the integration-test helper.").maxIterations(6).build());

        node = support.newNode(NODE, llm, OrcaRuntimeItSupport.options().codeSubagents(codeSubagents));
        node.writeFile(NOTE, "NOTE-BODY-3c90");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private static Map<String, Object> bash(String command) {
        return Map.of("command", command);
    }

    private static Map<String, Object> backgroundBash(String command) {
        return Map.of("command", command, "run_in_background", true);
    }

    private static Map<String, Object> task(String subagentName) {
        return Map.of("subagent_name", subagentName, "prompt", "do your part", "description", "delegate");
    }

    private static String taskIdIn(String text) {
        final Matcher matcher = TASK_ID.matcher(text);
        if (!matcher.find()) {
            throw new AssertionError("no background task id in: " + text);
        }
        return matcher.group();
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            if (!latch.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError(what + " did not happen within " + GATE_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for: " + what, e);
        }
    }

    // --- EE-49 -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a skill's hook fires in its fork and the fork's subagent, not in another session and not after it")
    void skillHookStaysInsideTheSkillsFork() {
        final SessionId sessionA = OrcaRuntimeItSupport.newSession();
        final SessionId sessionB = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionA.value(), WORKER);
        final String helperRoute = ScriptedLlmClient.forkRoute(sessionA.value(), HELPER);
        final CountDownLatch forkRunning = new CountDownLatch(1);
        final CountDownLatch sessionBDone = new CountDownLatch(1);

        llm.script(sessionA.value(), ScriptedLlmClient.callTool("Skill", Map.of("skill", GUARDED_SKILL)),
                ScriptedLlmClient.callTool("Bash", bash("echo AFTER-SKILL-5c18")), ScriptedLlmClient.text("a done"));
        // The fork parks on its first model call, so the skill is provably active while session B runs its turn.
        llm.scriptDynamic(forkRoute, call -> {
            forkRunning.countDown();
            await(sessionBDone, "session B's turn");
            return ScriptedLlmClient.callTool("Bash", bash("echo FORK-OUTPUT-2d44"));
        }, call -> ScriptedLlmClient.callTool("Task", task(HELPER)), call -> ScriptedLlmClient.text("fork finished"));
        llm.script(helperRoute, ScriptedLlmClient.callTool("Bash", bash("echo HELPER-OUTPUT-8a07")),
                ScriptedLlmClient.text("helper finished"));
        llm.script(sessionB.value(), ScriptedLlmClient.callTool("Bash", bash("echo SESSION-B-OUTPUT-9f31")),
                ScriptedLlmClient.text("b done"));

        final CompletableFuture<OrcaAgentExecutionResult> turnA = CompletableFuture
                .supplyAsync(() -> node.run(sessionA, "run the guarded skill"));
        final OrcaAgentExecutionResult resultB;
        try {
            await(forkRunning, "the skill's fork starting");
            resultB = node.run(sessionB, "run a command");
        } finally {
            sessionBDone.countDown();
        }
        final OrcaAgentExecutionResult resultA = turnA.orTimeout(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();

        assertThat(resultA.isSuccess()).isTrue();
        assertThat(resultB.isSuccess()).isTrue();
        // Another session of the same agent, while the skill is active: its Bash runs, unguarded.
        final List<String> seenByB = llm.lastCallFor(sessionB.value()).observations();
        assertThat(seenByB).anyMatch(observation -> observation.contains("SESSION-B-OUTPUT-9f31"));
        assertThat(seenByB).noneMatch(observation -> observation.contains(GUARD_REASON));
        // The fork: refused.
        final List<String> seenByFork = llm.lastCallFor(forkRoute).observations();
        assertThat(seenByFork).anyMatch(observation -> observation.contains(GUARD_REASON));
        assertThat(seenByFork).noneMatch(observation -> observation.startsWith("FORK-OUTPUT-2d44"));
        // The subagent the fork started: refused as well — the hook follows the fork's descendants.
        final List<String> seenByHelper = llm.lastCallFor(helperRoute).observations();
        assertThat(seenByHelper).anyMatch(observation -> observation.contains(GUARD_REASON));
        assertThat(seenByHelper).noneMatch(observation -> observation.startsWith("HELPER-OUTPUT-8a07"));
        // The session that invoked the skill, once the skill has returned: its own Bash runs.
        final List<String> seenByA = llm.lastCallFor(sessionA.value()).observations();
        assertThat(seenByA).anyMatch(observation -> observation.contains("AFTER-SKILL-5c18"));
        assertThat(seenByA).noneMatch(observation -> observation.contains(GUARD_REASON));
        // And the runtime's registry never held the hook.
        assertThat(node.hookRegistry().getHooks(HookEventType.PRE_TOOL)).isEmpty();
    }

    // --- EE-51 -----------------------------------------------------------------------------------------------------

    private void registerReadGuard(String name, boolean failOpen) {
        // A real shell command in the execution's environment that cannot answer inside its timeout.
        node.hookRegistry().register(HookEventType.PRE_TOOL,
                new DeclarativePreToolHook(name, NameOnlyPredicate.of("Read"),
                        new ShellAction("sleep 20", Duration.ofMillis(300)), new DefaultShellActionExecutor(), null,
                        null, Map.of(), DeclarativeHookOptions.builder().failOpen(failOpen).build()));
    }

    @Test
    @DisplayName("a preTool guard whose command cannot finish blocks the tool, and the model is told why")
    void guardThatCannotRunBlocksTheTool() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        registerReadGuard("it-guard", false);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", NOTE)),
                ScriptedLlmClient.text("answered"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "read the note");

        assertThat(result.isSuccess()).isTrue();
        final List<String> observations = llm.lastCallFor(sessionId.value()).observations();
        assertThat(observations).anyMatch(observation -> observation.contains("guard hook 'it-guard' (preTool)")
                && observation.contains("timed out") && observation.contains("fail-closed"));
        assertThat(observations).noneMatch(observation -> observation.contains("NOTE-BODY-3c90"));
        assertThat(observations).noneMatch(observation -> observation.contains("failOpen"));
    }

    @Test
    @DisplayName("the same hook declared failOpen lets the tool run when its command cannot finish")
    void failOpenHookThatCannotRunLetsTheToolRun() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        registerReadGuard("it-audit", true);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", NOTE)),
                ScriptedLlmClient.text("answered"));

        node.run(sessionId, "read the note");

        assertThat(llm.lastCallFor(sessionId.value()).observations())
                .anyMatch(observation -> observation.contains("NOTE-BODY-3c90"));
    }

    /**
     * A node whose skill parser accepts shell hooks and whose environment provider fails for the {@link #WORKER} fork
     * only, the way a sandbox provider fails: by throwing, or by answering with nothing.
     */
    private OrcaRuntimeItSupport.Node nodeWithFailingForkEnvironment(String name, boolean providerThrows) {
        support.seedSkill(name, SHELL_GUARDED_SKILL,
                "---\nname: " + SHELL_GUARDED_SKILL + "\ndescription: Runs in a fork with a shell guard on TodoWrite\n"
                        + "execution:\n  mode: fork\n  agent: " + WORKER + "\nhooks:\n  preTool:\n"
                        + "    - matcher: TodoWrite\n      action: { type: shell, command: \"exit 0\" }\n"
                        + "---\n\nDo the guarded work.\n");
        final InMemorySubagentRegistry codeSubagents = new InMemorySubagentRegistry();
        codeSubagents.register(Subagent.builder().name(WORKER).description("Integration-test worker subagent")
                .systemPrompt("You are the integration-test worker.").maxIterations(6).build());
        return support.newNode(name, llm, OrcaRuntimeItSupport.options().codeSubagents(codeSubagents)
                .skillShellHooks(true).environmentProvider(local -> request -> {
                    final boolean worker = request.fork().map(fork -> WORKER.equals(fork.name())).orElse(false);
                    if (!worker) {
                        return local.resolve(request);
                    }
                    if (providerThrows) {
                        throw new IllegalStateException(PROVIDER_DOWN);
                    }
                    return null;
                }));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "provider throws: {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @DisplayName("a skill's shell guard in a fork whose environment provider failed blocks the tool, with the cause")
    void skillShellGuardBlocksWhenTheForksEnvironmentProviderFails(boolean providerThrows) {
        final OrcaRuntimeItSupport.Node failing = nodeWithFailingForkEnvironment(
                providerThrows ? "agent-throwing-provider" : "agent-null-provider", providerThrows);
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), WORKER);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Skill", Map.of("skill", SHELL_GUARDED_SKILL)),
                ScriptedLlmClient.text("a done"));
        // TodoWrite needs no environment, so only the guard can stop it.
        llm.script(forkRoute,
                ScriptedLlmClient
                        .callTool("TodoWrite",
                                Map.of("todos",
                                        List.of(Map.of("content", "GUARDED-TODO-1f5a", "status", "in_progress",
                                                "activeForm", "Doing GUARDED-TODO-1f5a")))),
                ScriptedLlmClient.text("fork finished"));

        final OrcaAgentExecutionResult result = failing.run(sessionId, "run the shell-guarded skill");

        assertThat(result.isSuccess()).isTrue();
        final List<String> seenByFork = llm.lastCallFor(forkRoute).observations();
        assertThat(seenByFork)
                .anyMatch(observation -> observation.contains("guard hook '" + SHELL_GUARDED_SKILL + "' (preTool)")
                        && observation.contains("execution environment unavailable")
                        && observation.contains("fail-closed")
                        && observation.contains(providerThrows ? PROVIDER_DOWN : "returned no execution environment"));
        // A TodoWrite that ran would answer "Todo list updated ... In Progress: Doing GUARDED-TODO-1f5a".
        assertThat(seenByFork).noneMatch(
                observation -> observation.contains("GUARDED-TODO-1f5a") || observation.contains("Todo list updated"));
    }

    // --- EE-58 -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("another session of the same runtime cannot read or stop a background command; its own session can")
    void backgroundCommandIsInvisibleToAnotherSession() {
        final SessionId sessionA = OrcaRuntimeItSupport.newSession();
        final SessionId sessionB = OrcaRuntimeItSupport.newSession();
        final AtomicReference<String> taskId = new AtomicReference<>();
        llm.scriptDynamic(sessionA.value(),
                call -> ScriptedLlmClient.callTool("Bash", backgroundBash("echo SECRET-OUTPUT-61b2; exec sleep 120")),
                call -> {
                    taskId.set(taskIdIn(call.lastObservation()));
                    return ScriptedLlmClient.text("started");
                });
        node.run(sessionA, "start a server");

        // Two turns rather than one: back-to-back iterations whose tool calls all fail end a turn as stalled.
        llm.script(sessionB.value(), ScriptedLlmClient.callTool("KillShell", Map.of("taskId", taskId.get())),
                ScriptedLlmClient.callTool("BashOutput", Map.of("taskId", "bash_deadbeef", "block", false)),
                ScriptedLlmClient.text("b done"));
        node.run(sessionB, "stop somebody else's server");
        final List<String> killAttempt = llm.lastCallFor(sessionB.value()).observations();
        llm.script(sessionB.value(),
                ScriptedLlmClient.callTool("BashOutput", Map.of("taskId", taskId.get(), "block", false)),
                ScriptedLlmClient.text("b done"));
        node.run(sessionB, "read somebody else's server");
        final List<String> readAttempt = llm.lastCallFor(sessionB.value()).observations();

        // Session B is told what it would be told about an id that never existed — to the character.
        final String unknownId = killAttempt.get(killAttempt.size() - 1).replace("bash_deadbeef", taskId.get());
        assertThat(unknownId).contains("Shell not found: " + taskId.get());
        assertThat(killAttempt.get(killAttempt.size() - 2)).isEqualTo(unknownId);
        assertThat(readAttempt.get(readAttempt.size() - 1)).isEqualTo(unknownId);
        assertThat(readAttempt).noneMatch(observation -> observation.contains("SECRET-OUTPUT-61b2"));

        // The command survived that, and a later turn of its own session stops it.
        llm.script(sessionA.value(), ScriptedLlmClient.callTool("KillShell", Map.of("taskId", taskId.get())),
                ScriptedLlmClient.callTool("BashOutput", Map.of("taskId", taskId.get(), "wait_up_to", 10)),
                ScriptedLlmClient.text("stopped"));
        node.run(sessionA, "stop my server");

        final List<String> seenByA = llm.lastCallFor(sessionA.value()).observations();
        assertThat(seenByA).noneMatch(observation -> observation.contains("Shell not found"));
        assertThat(seenByA).anyMatch(observation -> observation.contains("Status: Killed"));
    }

    @Test
    @DisplayName("a session's turn reads and stops the background command its fork started")
    void parentTurnReachesItsForksBackgroundCommand() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), WORKER);
        final AtomicReference<String> taskId = new AtomicReference<>();
        llm.scriptDynamic(forkRoute,
                call -> ScriptedLlmClient.callTool("Bash", backgroundBash("echo FORK-SERVER-77d0; exec sleep 120")),
                call -> ScriptedLlmClient.text("started " + taskIdIn(call.lastObservation())));
        llm.scriptDynamic(sessionId.value(), call -> ScriptedLlmClient.callTool("Task", task(WORKER)), call -> {
            taskId.set(taskIdIn(call.lastObservation()));
            return ScriptedLlmClient.callTool("KillShell", Map.of("taskId", taskId.get()));
        }, call -> ScriptedLlmClient.callTool("BashOutput", Map.of("taskId", taskId.get(), "wait_up_to", 10)),
                call -> ScriptedLlmClient.text("stopped"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "delegate a server, then stop it");

        assertThat(result.isSuccess()).isTrue();
        // The fork ran under an execution id of its own; what joins it to this turn is the session it acted for.
        final List<String> observations = llm.lastCallFor(sessionId.value()).observations();
        assertThat(observations).noneMatch(observation -> observation.contains("Shell not found"));
        assertThat(observations).anyMatch(observation -> observation.contains("Status: Killed"));
    }
}
