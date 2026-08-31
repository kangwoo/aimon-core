package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.tools.ToolContextKeys;

/**
 * L4 — two assembled runtimes in one process must not reach into each other.
 *
 * <p>
 * This is the file the whole suite was built for: "여러 에이전트가 동시에 돌 때 영역 침범이 없는가". Everything below asserts
 * <b>absence</b> — that agent-b cannot see what agent-a wrote, was not offered what agent-a registered, and does not
 * stop working when agent-a is torn down. Absence is only meaningful if the positive half is also asserted, so every
 * test here pairs "b cannot see it" with "a can" — otherwise a runtime that silently did nothing would pass.
 *
 * <p>
 * <b>Bash is excluded on purpose.</b> The default {@code VirtualShell} each runtime gets has no default working
 * directory, so every agent's shell commands run in the same JVM directory; there is no per-runtime confinement to
 * assert. That is pinned in {@code BashToolTurnIntegrationTest} rather than papered over here.
 */
@DisplayName("RT-IT-L4: isolation between agent runtimes")
class MultiRuntimeIsolationIntegrationTest {

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node agentA;
    private OrcaRuntimeItSupport.Node agentB;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
        // One barrier, both agents: the two runtimes are otherwise disjoint by construction, so the only thing they
        // share is the synchronisation point that forces their turns to overlap.
        final CyclicBarrier barrier = new CyclicBarrier(2);
        agentA = support.newNode("agent-a", llm, OrcaRuntimeItSupport.options()
                .extraToolProvider(ContextProbeTool.provider()).extraToolProvider(RendezvousTool.provider(barrier)));
        agentB = support.newNode("agent-b", llm, OrcaRuntimeItSupport.options()
                .extraToolProvider(ContextProbeTool.provider()).extraToolProvider(RendezvousTool.provider(barrier)));
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    /**
     * The reading turn tries all three ways an agent could name another agent's file, because only one of them is an
     * isolation claim at all.
     *
     * <p>
     * Reading the bare name {@code secret.txt} from agent-b resolves under agent-b's own root, so the miss is
     * guaranteed by the directory layout and would hold even if the two roots were nested inside one another. The
     * relative escape and the absolute path are the attempts that actually have somewhere to go, and they are the ones
     * the runtime has to refuse.
     *
     * <p>
     * The successful write in the middle is not decoration. It is a control proving agent-b's file tools work at all
     * (three failing reads in a row would otherwise be indistinguishable from a broken tool), and it keeps the turn
     * under the executor's no-progress guard, which aborts after three consecutive tool-only iterations in which every
     * call failed — that guard is pinned on its own in {@code FileToolTurnIntegrationTest}.
     */
    @Test
    @DisplayName("a file one agent writes stays unreachable from the other agent's turn, by any path")
    void writesAreInvisibleToTheOtherAgent() {
        final SessionId writing = OrcaRuntimeItSupport.newSession();
        final SessionId reading = OrcaRuntimeItSupport.newSession();
        final String absolutePathToAgentAsFile = agentA.root().resolve("secret.txt").toString();
        llm.script(writing.value(),
                ScriptedLlmClient.callTool("Write", Map.of("file_path", "secret.txt", "content", "AGENT-A-ONLY-3d71")),
                ScriptedLlmClient.text("written"));
        llm.script(reading.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", "secret.txt")),
                ScriptedLlmClient.callTool("Read", Map.of("file_path", "../agent-a/secret.txt")),
                ScriptedLlmClient.callTool("Write", Map.of("file_path", "mine.txt", "content", "AGENT-B-OWN")),
                ScriptedLlmClient.callTool("Read", Map.of("file_path", absolutePathToAgentAsFile)),
                ScriptedLlmClient.text("could not read"));

        agentA.run(writing, "write a private note");
        final OrcaAgentExecutionResult readResult = agentB.run(reading, "read agent-a's note");

        // The write really happened — without this half, a Write tool that silently no-ops would pass the test.
        assertThat(agentA.readFile("secret.txt")).contains("AGENT-A-ONLY-3d71");
        assertThat(readResult.isSuccess()).isTrue();

        // The last call carries every observation of the turn, once each and in order. Flattening across calls would
        // repeat the earlier ones, since each call re-sends the accumulated transcript.
        final List<String> observations = llm.lastCallFor(reading.value()).observations();
        assertThat(observations).hasSize(4).noneMatch(text -> text.contains("AGENT-A-ONLY-3d71"));
        // The bare name is a plain miss inside agent-b's own root; the two escapes are refused as traversal, which is
        // the security property rather than an accident of where the temp directories happen to sit.
        assertThat(observations.get(0)).contains("File not found");
        assertThat(observations.get(1)).contains("Path traversal detected");
        assertThat(observations.get(2)).doesNotContain("Error");
        assertThat(observations.get(3)).contains("Path traversal detected");
        assertThat(agentB.fileExists("secret.txt")).isFalse();
        assertThat(agentB.readFile("mine.txt")).contains("AGENT-B-OWN");
    }

    @Test
    @DisplayName("a tool registered on one runtime is not offered to another agent's model")
    void toolRegistrationsDoNotLeakBetweenRuntimes() {
        // agent-c is assembled without the probe that agent-a and agent-b carry.
        final OrcaRuntimeItSupport.Node agentC = support.newNode("agent-c", llm);
        final SessionId onA = OrcaRuntimeItSupport.newSession();
        final SessionId onC = OrcaRuntimeItSupport.newSession();
        llm.script(onA.value(), ScriptedLlmClient.text("a done"));
        llm.script(onC.value(), ScriptedLlmClient.text("c done"));

        agentA.run(onA, "hello");
        agentC.run(onC, "hello");

        // Distinct registry instances is the mechanism; what the model was offered is the consequence. Assert the
        // consequence too — a shared registry would be invisible in an identity check that someone later "fixed".
        assertThat(agentA.runtime().getToolRegistry()).isNotSameAs(agentC.runtime().getToolRegistry());
        assertThat(llm.firstCallFor(onA.value()).toolNames()).contains(ContextProbeTool.TOOL_NAME);
        assertThat(llm.firstCallFor(onC.value()).toolNames()).doesNotContain(ContextProbeTool.TOOL_NAME);
        // The production tools are still there on both — the difference is only the opt-in probe.
        assertThat(llm.firstCallFor(onC.value()).toolNames()).contains("Read", "Write", "Task", "Skill");
    }

    @Test
    @DisplayName("each runtime reports its own agent runtime id to its tools")
    void agentRuntimeIdsAreDistinct() {
        final SessionId onA = OrcaRuntimeItSupport.newSession();
        final SessionId onB = OrcaRuntimeItSupport.newSession();
        llm.script(onA.value(), ScriptedLlmClient.callTool("ContextProbe", Map.of()), ScriptedLlmClient.text("a"));
        llm.script(onB.value(), ScriptedLlmClient.callTool("ContextProbe", Map.of()), ScriptedLlmClient.text("b"));

        agentA.run(onA, "who am I");
        agentB.run(onB, "who am I");

        // The id every agent-scoped store keys on. Were these equal, two agents would share their tool state even
        // though their file systems are separate.
        assertThat(runtimeIdSeenBy(onA)).isEqualTo("agent:agent-a");
        assertThat(runtimeIdSeenBy(onB)).isEqualTo("agent:agent-b");
    }

    @Test
    @DisplayName("closing one runtime leaves the other able to run a turn")
    void closingOneRuntimeDoesNotBreakTheOther() {
        final SessionId beforeClose = OrcaRuntimeItSupport.newSession();
        final SessionId afterClose = OrcaRuntimeItSupport.newSession();
        llm.script(beforeClose.value(), ScriptedLlmClient.text("b is fine"));
        llm.script(afterClose.value(),
                ScriptedLlmClient.callTool("Write", Map.of("file_path", "still-working.txt", "content", "yes")),
                ScriptedLlmClient.text("b still writes"));

        agentB.run(beforeClose, "warm up");
        agentA.runtime().close();
        final OrcaAgentExecutionResult result = agentB.run(afterClose, "keep working");

        // Per the scope model, a runtime owns only its own collaborators; tearing one down must not reach the
        // application-scoped or per-node ones another runtime is still using.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("b still writes");
        assertThat(agentB.readFile("still-working.txt")).contains("yes");
    }

    /**
     * The two turns are pinned <b>between</b> their write and their read by a shared barrier, which is what turns this
     * from a sequencing test into an interference test.
     *
     * <p>
     * With both writes landed and neither read issued, the window where a shared file system would hand agent-a's read
     * agent-b's bytes is held open until both parties are inside it. A start latch cannot do that: it is released
     * before {@code execute(...)}, so agent-a's whole turn can complete before agent-b's thread runs, and the file
     * name they "collide" on is never actually contended.
     */
    @Test
    @DisplayName("two agents running turns at the same time do not see each other's observations")
    void concurrentTurnsDoNotMixObservations() throws Exception {
        final SessionId onA = OrcaRuntimeItSupport.newSession();
        final SessionId onB = OrcaRuntimeItSupport.newSession();
        // Each agent writes its own sentinel and reads it straight back, so both turns touch the same file *name*
        // concurrently — the collision that a shared file system would turn into cross-talk.
        llm.script(onA.value(),
                ScriptedLlmClient.callTool("Write", Map.of("file_path", "shared-name.txt", "content", "FROM-A-8c40")),
                ScriptedLlmClient.callTool("Rendezvous", Map.of()),
                ScriptedLlmClient.callTool("Read", Map.of("file_path", "shared-name.txt")),
                ScriptedLlmClient.text("a finished"));
        llm.script(onB.value(),
                ScriptedLlmClient.callTool("Write", Map.of("file_path", "shared-name.txt", "content", "FROM-B-2f95")),
                ScriptedLlmClient.callTool("Rendezvous", Map.of()),
                ScriptedLlmClient.callTool("Read", Map.of("file_path", "shared-name.txt")),
                ScriptedLlmClient.text("b finished"));

        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final Future<OrcaAgentExecutionResult> futureA = pool.submit(() -> agentA.run(onA, "write and read back"));
            final Future<OrcaAgentExecutionResult> futureB = pool.submit(() -> agentB.run(onB, "write and read back"));

            assertThat(futureA.get(60, TimeUnit.SECONDS).getFinalAnswer()).isEqualTo("a finished");
            assertThat(futureB.get(60, TimeUnit.SECONDS).getFinalAnswer()).isEqualTo("b finished");
        } finally {
            pool.shutdownNow();
        }

        // Both turns were provably inside the window at the same moment; a timeout would appear here as an error
        // observation instead, and the isolation assertions below would be describing sequential runs again.
        assertThat(observationsOf(onA)).anyMatch(text -> text.startsWith(RendezvousTool.ARRIVED));
        assertThat(observationsOf(onB)).anyMatch(text -> text.startsWith(RendezvousTool.ARRIVED));

        assertThat(observationsOf(onA)).anyMatch(text -> text.contains("FROM-A-8c40"))
                .noneMatch(text -> text.contains("FROM-B-2f95"));
        assertThat(observationsOf(onB)).anyMatch(text -> text.contains("FROM-B-2f95"))
                .noneMatch(text -> text.contains("FROM-A-8c40"));
        assertThat(agentA.readFile("shared-name.txt")).contains("FROM-A-8c40");
        assertThat(agentB.readFile("shared-name.txt")).contains("FROM-B-2f95");
    }

    /** Every observation the session saw, flattened across its calls. */
    private List<String> observationsOf(SessionId sessionId) {
        return llm.callsFor(sessionId.value()).stream().flatMap(call -> call.observations().stream()).toList();
    }

    private String runtimeIdSeenBy(SessionId sessionId) {
        return ContextProbeTool.presentValueOf(llm.lastCallFor(sessionId.value()).lastObservation(),
                ToolContextKeys.AGENT_RUNTIME_ID.name());
    }
}
