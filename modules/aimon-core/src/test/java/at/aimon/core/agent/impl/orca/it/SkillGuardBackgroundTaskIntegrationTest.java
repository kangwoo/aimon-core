package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;

/**
 * L2 — a background {@code Task} started inside a skill's fork, against the skill's hooks (EE-69).
 *
 * <p>
 * A skill's hooks are a layer over the registry its fork dispatches against, and the layer is switched off when the
 * {@code Skill} tool returns. A subagent the fork started with {@code run_in_background} does not return with it:
 * it goes on running, and from then on only the runtime's hooks see it. For a skill whose hooks are guards that is a
 * subagent running a tool the guard was there to stop. Every test here runs the real assembly — skill registry,
 * {@code Skill} tool, fork, {@code Task} tool, background subagent — and parks the background subagent until the
 * skill has provably returned.
 */
@DisplayName("RT-IT-L2: a background Task started inside a skill's fork, against the skill's hooks")
@DisabledOnOs(OS.WINDOWS)
class SkillGuardBackgroundTaskIntegrationTest {

    private static final String NODE = "agent-bg-task";
    private static final String WORKER = "it-worker";
    private static final String HELPER = "it-helper";
    private static final String GUARDED_SKILL = "it-guarded";
    private static final String OBSERVING_SKILL = "it-observing";
    private static final String GUARD_REASON = "SKILL-GUARD-7e21";
    private static final String HELPER_OUTPUT = "HELPER-AFTER-SKILL-41d7";
    private static final String LAUNCHED = "Background task launched successfully";
    private static final int GATE_TIMEOUT_SECONDS = 30;

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node node;

    private final CountDownLatch skillReturned = new CountDownLatch(1);
    private final CountDownLatch helperFinished = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();

        // A fork-mode skill whose only hook refuses Bash: a guard.
        support.seedSkill(NODE, GUARDED_SKILL,
                "---\nname: " + GUARDED_SKILL + "\ndescription: Runs in a fork that may not use Bash\n"
                        + "execution:\n  mode: fork\n  agent: " + WORKER + "\nhooks:\n  preTool:\n"
                        + "    - matcher: Bash\n      action: { type: deny, reason: \"" + GUARD_REASON + "\" }\n"
                        + "---\n\nDo the guarded work.\n");
        // And one whose only hook observes: postTool cannot block anything.
        support.seedSkill(NODE, OBSERVING_SKILL,
                "---\nname: " + OBSERVING_SKILL + "\ndescription: Runs in a fork that is only audited\n"
                        + "execution:\n  mode: fork\n  agent: " + WORKER + "\nhooks:\n  postTool:\n"
                        + "    - matcher: Bash\n      action: { type: shell, command: \"exit 0\" }\n"
                        + "---\n\nDo the audited work.\n");

        final InMemorySubagentRegistry codeSubagents = new InMemorySubagentRegistry();
        codeSubagents.register(Subagent.builder().name(WORKER).description("Integration-test worker subagent")
                .systemPrompt("You are the integration-test worker.").maxIterations(6).build());
        codeSubagents.register(Subagent.builder().name(HELPER).description("Integration-test helper subagent")
                .systemPrompt("You are the integration-test helper.").maxIterations(6).build());

        node = support.newNode(NODE, llm,
                OrcaRuntimeItSupport.options().codeSubagents(codeSubagents).skillShellHooks(true));
    }

    @AfterEach
    void tearDown() {
        // Never leave the background subagent parked on a failed assertion.
        skillReturned.countDown();
        support.close();
    }

    @Test
    @DisplayName("under a guard skill the background Task is refused: nothing is left to outlive the guard")
    void backgroundTaskInsideAGuardedSkillForkIsRefused() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), WORKER);
        final String helperRoute = ScriptedLlmClient.forkRoute(sessionId.value(), HELPER);
        scriptSkillThatStartsABackgroundHelper(sessionId, GUARDED_SKILL, forkRoute, helperRoute);

        final OrcaAgentExecutionResult result = node.run(sessionId, "run the guarded skill");

        assertThat(result.isSuccess()).isTrue();
        final List<String> seenByFork = llm.lastCallFor(forkRoute).observations();
        // What happened without the refusal, stated first so a regression reads as what it is: the helper started,
        // waited for the skill to return, and then ran the Bash the skill's guard denies.
        assertThat(llm.callCount(helperRoute))
                .as("the background helper ran after the skill returned and saw: %s",
                        llm.callCount(helperRoute) == 0 ? List.of() : llm.lastCallFor(helperRoute).observations())
                .isZero();
        assertThat(seenByFork).noneMatch(observation -> observation.contains(LAUNCHED));
        assertThat(seenByFork).anyMatch(observation -> observation.contains("Background mode is not available here")
                && observation.contains("skill '" + GUARDED_SKILL + "'") && observation.contains("guard hooks")
                && observation.contains("background subagent"));
    }

    @Test
    @DisplayName("under a guard skill a foreground Task still runs, under the guard")
    void foregroundTaskInsideAGuardedSkillForkIsUnaffected() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), WORKER);
        final String helperRoute = ScriptedLlmClient.forkRoute(sessionId.value(), HELPER);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Skill", Map.of("skill", GUARDED_SKILL)),
                ScriptedLlmClient.text("done"));
        llm.script(forkRoute, ScriptedLlmClient.callTool("Task", task(false)), ScriptedLlmClient.text("fork finished"));
        llm.script(helperRoute, ScriptedLlmClient.callTool("Bash", Map.of("command", "echo " + HELPER_OUTPUT)),
                ScriptedLlmClient.text("helper finished"));

        assertThat(node.run(sessionId, "run the guarded skill").isSuccess()).isTrue();

        final List<String> seenByHelper = llm.lastCallFor(helperRoute).observations();
        assertThat(seenByHelper).anyMatch(observation -> observation.contains(GUARD_REASON));
        assertThat(seenByHelper).noneMatch(observation -> observation.startsWith(HELPER_OUTPUT));
    }

    @Test
    @DisplayName("under a skill whose hooks only observe, the background Task runs and outlives the skill")
    void backgroundTaskInsideAnObservingSkillForkRuns() throws Exception {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), WORKER);
        final String helperRoute = ScriptedLlmClient.forkRoute(sessionId.value(), HELPER);
        scriptSkillThatStartsABackgroundHelper(sessionId, OBSERVING_SKILL, forkRoute, helperRoute);

        final OrcaAgentExecutionResult result = node.run(sessionId, "run the audited skill");

        assertThat(result.isSuccess()).isTrue();
        assertThat(llm.lastCallFor(forkRoute).observations()).anyMatch(observation -> observation.contains(LAUNCHED));
        assertThat(helperFinished.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(llm.lastCallFor(helperRoute).observations())
                .anyMatch(observation -> observation.startsWith(HELPER_OUTPUT));
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    /**
     * The session runs the skill; the skill's fork starts {@code HELPER} in the background and answers; the helper
     * parks on its first model call until the skill has returned to the session, then runs Bash.
     */
    private void scriptSkillThatStartsABackgroundHelper(SessionId sessionId, String skill, String forkRoute,
            String helperRoute) {
        llm.scriptDynamic(sessionId.value(), call -> ScriptedLlmClient.callTool("Skill", Map.of("skill", skill)),
                call -> {
                    // The Skill tool has returned: the skill's hook layer is off from here on.
                    skillReturned.countDown();
                    if (call.lastObservation().contains(LAUNCHED)) {
                        awaitHelper();
                    }
                    return ScriptedLlmClient.text("done");
                });
        llm.scriptDynamic(forkRoute, call -> ScriptedLlmClient.callTool("Task", task(true)),
                // The Task tool's answer is the fork's answer, so the session can tell whether a helper is running.
                call -> ScriptedLlmClient.text("fork finished: " + call.lastObservation()));
        llm.scriptDynamic(helperRoute, call -> {
            awaitQuietly(skillReturned);
            return ScriptedLlmClient.callTool("Bash", Map.of("command", "echo " + HELPER_OUTPUT));
        }, call -> {
            helperFinished.countDown();
            return ScriptedLlmClient.text("helper finished");
        });
    }

    private void awaitHelper() {
        awaitQuietly(helperFinished);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("gate not released within " + GATE_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        }
    }

    private static Map<String, Object> task(boolean background) {
        final Map<String, Object> input = new HashMap<>(
                Map.of("subagent_name", HELPER, "prompt", "do your part", "description", "delegate"));
        if (background) {
            input.put("run_in_background", true);
        }
        return input;
    }
}
