package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;

/**
 * L2 — slash commands through an assembled runtime.
 *
 * <p>
 * A command takes a <b>different branch of the executor</b> than everything else in this suite: {@code execute()}
 * routes
 * to {@code executeCommandFlow} instead of the ReAct loop, so no LLM call is made at all and the answer comes from the
 * command's own {@code CommandExecutionResult}. That split is what these tests cover — the registry the factory
 * assembled is the one resolving the name, and the command's effect on session state is real.
 *
 * <p>
 * The most load-bearing case is {@link #clearDropsTheHistoryTheNextTurnWouldHaveCarried}: {@code /clear} is the only
 * command here that mutates session state, and its effect is only observable one turn later, in what the <em>next</em>
 * turn's prompt does not contain. Note what it does <b>not</b> assert — that the transcript is empty. It is not:
 * {@code executeCommandFlow} appends the command's own response as an assistant message <em>after</em> the clear, so a
 * cleared session immediately holds one message again. Asserting emptiness would fail for a reason that has nothing to
 * do with clearing.
 */
@DisplayName("RT-IT-L2: slash commands through an assembled runtime")
class CommandTurnIntegrationTest {

    private static final String NODE = "agent-a";
    private static final String WORKER = "it-command-worker";

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();

        support.seedSkill(NODE, "it-listed",
                "---\nname: it-listed\ndescription: Seeded so /skills has something to list\n---\n\nbody\n");

        final InMemorySubagentRegistry codeSubagents = new InMemorySubagentRegistry();
        codeSubagents.register(Subagent.builder().name(WORKER).description("Seeded so /agents has something to list")
                .systemPrompt("You are the command-suite worker.").build());

        node = support.newNode(NODE, llm, OrcaRuntimeItSupport.options().codeSubagents(codeSubagents));
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    @Test
    @DisplayName("a direct command answers without any LLM call")
    void directCommandBypassesTheModelEntirely() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();

        final OrcaAgentExecutionResult result = node.run(sessionId, "/version");

        assertThat(result.isSuccess()).isTrue();
        // The version string the harness passed to the factory constructor — proof the answer came from the assembled
        // command instance and not from some default.
        assertThat(result.getFinalAnswer()).contains("1.0.0");
        // The whole point of a DirectExecutable: zero LLM traffic. A regression that routed commands through the ReAct
        // loop would still produce a plausible answer here (the global fallback), so counting the calls is the only
        // assertion that can see it.
        assertThat(llm.callCount(sessionId.value())).isZero();
        assertThat(llm.totalCalls()).isZero();
        // No iterations either — a command is not a ReAct turn.
        assertThat(result.getIterationCount()).isZero();
    }

    @Test
    @DisplayName("/clear drops the history the next turn would otherwise have carried")
    void clearDropsTheHistoryTheNextTurnWouldHaveCarried() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("noted"), ScriptedLlmClient.text("carried"),
                ScriptedLlmClient.text("after the clear"));

        node.run(sessionId, "remember SENTINEL-CLEAR-3f81");
        node.run(sessionId, "still here?");

        // Positive control first: without it, the post-clear absence below would also hold in a runtime that never
        // carried history at all.
        assertThat(llm.lastCallFor(sessionId.value()).allText()).anyMatch(text -> text.contains("SENTINEL-CLEAR-3f81"));

        final OrcaAgentExecutionResult cleared = node.run(sessionId, "/clear");
        assertThat(cleared.isSuccess()).isTrue();
        assertThat(cleared.getFinalAnswer()).contains("Conversation cleared");

        node.run(sessionId, "what did I ask you to remember");

        // The sentinel is gone from the prompt, which is the only place clearing is observable. The transcript itself
        // is not empty — the command's own response was appended to it after the clear.
        assertThat(llm.lastCallFor(sessionId.value()).allText())
                .noneMatch(text -> text.contains("SENTINEL-CLEAR-3f81"));
        // Three LLM calls, not four: the /clear turn made none.
        assertThat(llm.callCount(sessionId.value())).isEqualTo(3);
    }

    @Test
    @DisplayName("the listing commands read the registries the factory assembled")
    void listingCommandsReadTheAssembledRegistries() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();

        // Each of these resolves a different registry through OrcaSystemCommandProvider. A registry that stopped being
        // threaded into the provider would leave the command listing nothing while still succeeding.
        assertThat(node.run(sessionId, "/agents").getFinalAnswer()).contains(WORKER);
        assertThat(node.run(sessionId, "/skills").getFinalAnswer()).contains("it-listed");
        assertThat(node.run(sessionId, "/commands").getFinalAnswer()).contains("clear").contains("version");
    }

    @Test
    @DisplayName("an unknown command fails the turn as a command error, not as a prompt to the model")
    void unknownCommandFailsWithoutReachingTheModel() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();

        final OrcaAgentExecutionResult result = node.run(sessionId, "/no-such-command");

        // isCommand() keys on the leading slash alone, so an unknown name is still a command — it must not silently
        // fall back to being sent to the model as ordinary user text.
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Command not found: no-such-command");
        assertThat(llm.callCount(sessionId.value())).isZero();
    }

    @Test
    @DisplayName("a command turn and a ReAct turn share one session's history")
    void commandOutputJoinsTheSameTranscriptAsReActTurns() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("first"), ScriptedLlmClient.text("second"));

        node.run(sessionId, "say something");
        node.run(sessionId, "/version");
        node.run(sessionId, "say something else");

        // The command's response was appended to the same transcript, so the next ReAct turn sees it. If the two flows
        // wrote to different buffers, the model would get a history with a hole in it exactly where a command ran.
        assertThat(llm.lastCallFor(sessionId.value()).allText()).anyMatch(text -> text.contains("1.0.0"));
    }
}
