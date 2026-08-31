package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.DefaultLiveSession;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnSessionEndContext;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.execution.HookResult;

/**
 * L3 — <b>when</b> each hook fires, which is the part of the hook contract no unit test can settle.
 *
 * <p>
 * That a registered hook gets called is unit-testable against a hook execution manager. What is only observable through
 * an assembled runtime is the firing <em>point</em>: which lifetime a hook is bound to, and what a caller may therefore
 * conclude from having been called. Every assertion here is about that.
 *
 * <h2>The session hooks are misnamed, and this class pins the real behaviour</h2>
 *
 * <p>
 * {@code docs/overview/scope-model.md} §6 lists {@code OnSessionStartHook} / {@code OnSessionEndHook} as known
 * misnomers:
 * they fire on {@link DefaultLiveSession} <b>open and close</b>, not on the lifetime of the session record. Because one
 * {@code SessionRecord} may be served by many live handles over time (idle eviction, restart, node handoff), a hook
 * that
 * reads its own name and assumes "once per session" is wrong — it will fire again every time the session is picked back
 * up. {@link #reopeningTheSameSessionFiresOnSessionStartAgain} is the test that states this out loud; if the hooks are
 * ever rescoped to match their names, it is the test that must be deliberately changed.
 *
 * <h2>Turn scope is pinned against session scope, not on its own</h2>
 *
 * <p>
 * {@code OnStart} / {@code OnStop} bracket a <em>turn</em> and {@code OnSessionStart} brackets a <em>handle</em>. Both
 * counts are asserted from the same two-turn run in
 * {@link #turnHooksFireOncePerTurnWhileTheSessionHookFiredOnlyAtOpen}, because either count alone would survive the two
 * scopes being collapsed into one.
 *
 * <h2>The tool hooks are asserted by their effect, not by their invocation</h2>
 *
 * <p>
 * Counting {@code PreTool} calls proves only that a listener list was walked. What the contract actually promises is
 * that a block prevents the tool from running, that a rewritten input is what the tool acts on, and that a rewritten
 * output is what the <em>model</em> subsequently sees. Those three are asserted against the file system and against the
 * observation recorded by {@link ScriptedLlmClient}, so a hook chain reduced to a no-op notifier fails them.
 */
@DisplayName("RT-IT-L3: where each hook fires — handle lifetime, turn lifetime, and tool dispatch")
class HookFiringIntegrationTest {

    private static final String NOTE = "note.txt";

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node node;

    /** Firing order across every hook type, appended to by the hooks registered per test. */
    private final List<String> fired = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
        node = support.newNode("agent-a", llm);
        node.writeFile(NOTE, "seeded");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    /** One tool call, then an answer. */
    private void scriptReadThenAnswer(SessionId sessionId) {
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", NOTE)),
                ScriptedLlmClient.text("answered"));
    }

    private List<String> firedOfType(String prefix) {
        return fired.stream().filter(e -> e.startsWith(prefix)).toList();
    }

    private void recordSessionHooks() {
        node.hookRegistry().register(HookEventType.ON_SESSION_START,
                (OnSessionStartContext ctx) -> record("sessionStart:" + ctx.getSessionId().orElseThrow().value()));
        node.hookRegistry().register(HookEventType.ON_SESSION_END,
                (OnSessionEndContext ctx) -> record("sessionEnd:" + ctx.getSessionId().orElseThrow().value()));
    }

    private HookResult record(String event) {
        fired.add(event);
        return HookResult.success();
    }

    /**
     * The session hook is bound to the handle, and the handle exists before any turn does — so the hook has already
     * fired by the time a caller could submit anything, and running a turn adds nothing.
     */
    @Test
    @DisplayName("OnSessionStart fires when the live session opens, not when a turn runs")
    void onSessionStartFiresAtOpenAndNotPerTurn() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptReadThenAnswer(sessionId);
        recordSessionHooks();

        final DefaultLiveSession session = node.openLiveSession(sessionId);
        assertThat(firedOfType("sessionStart")).containsExactly("sessionStart:" + sessionId.value());

        session.submit("read the note", SubmitOptions.empty());

        assertThat(firedOfType("sessionStart")).hasSize(1);
        assertThat(firedOfType("sessionEnd")).isEmpty();
    }

    /**
     * The misnomer, stated as an assertion. Same {@code SessionId}, second handle, and the "session start" hook fires a
     * second time — which is exactly what a restart, an idle eviction or a node handoff looks like from the hook's
     * seat.
     *
     * <p>
     * A hook that provisions something once per session (seeding a workspace, charging a setup fee) is wrong under this
     * behaviour, and the name is what would mislead its author. If this test ever needs changing, the docs' misnomer
     * list needs changing with it.
     */
    @Test
    @DisplayName("reopening the same session id fires OnSessionStart again — the hook is per handle, not per session")
    void reopeningTheSameSessionFiresOnSessionStartAgain() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        recordSessionHooks();

        node.openLiveSession(sessionId).close();
        node.openLiveSession(sessionId);

        assertThat(firedOfType("sessionStart")).containsExactly("sessionStart:" + sessionId.value(),
                "sessionStart:" + sessionId.value());
        assertThat(firedOfType("sessionEnd")).containsExactly("sessionEnd:" + sessionId.value());
    }

    /**
     * Close is idempotent with respect to the hook. This is load-bearing beyond tidiness: a handle is routinely closed
     * by an eviction sweep and again by an owning shutdown path, and a hook that fires twice would double-count every
     * evicted session.
     */
    @Test
    @DisplayName("OnSessionEnd fires once on close, and a second close does not fire it again")
    void onSessionEndFiresExactlyOncePerHandle() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final List<OnSessionEndContext> contexts = new ArrayList<>();
        node.hookRegistry().register(HookEventType.ON_SESSION_END, (OnSessionEndContext ctx) -> {
            contexts.add(ctx);
            return HookResult.success();
        });

        final DefaultLiveSession session = node.openLiveSession(sessionId);
        session.close();
        session.close();

        assertThat(contexts).hasSize(1);
        assertThat(contexts.get(0).getSessionId()).contains(sessionId);
        assertThat(contexts.get(0).isClean()).isTrue();
        // The agent-scoped runtime id, not the session id: the handle closed, the agent did not.
        assertThat(contexts.get(0).getAgentRuntimeId()).isEqualTo("agent:agent-a");
    }

    /**
     * Turn scope against handle scope, from one run. Two turns on one handle: the turn hooks fire twice, the session
     * hook stays at the one firing it did at open, and the end hook has not fired at all because the handle is still
     * open.
     */
    @Test
    @DisplayName("OnStart/OnStop fire once per turn while the session hook fired only at open")
    void turnHooksFireOncePerTurnWhileTheSessionHookFiredOnlyAtOpen() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("first"), ScriptedLlmClient.text("second"));
        recordSessionHooks();
        node.hookRegistry().register(HookEventType.ON_START, ctx -> record("turnStart"));
        node.hookRegistry().register(HookEventType.ON_STOP, ctx -> record("turnStop"));

        final DefaultLiveSession session = node.openLiveSession(sessionId);
        session.submit("one", SubmitOptions.empty());
        session.submit("two", SubmitOptions.empty());

        assertThat(firedOfType("turnStart")).hasSize(2);
        assertThat(firedOfType("turnStop")).hasSize(2);
        assertThat(firedOfType("sessionStart")).hasSize(1);
        assertThat(firedOfType("sessionEnd")).isEmpty();
    }

    /**
     * The tool hooks bracket the dispatch, in that order, naming the tool that is about to run and the one that just
     * did. The ordering is the whole claim — a chain that fired both after the tool would satisfy any count-based
     * assertion.
     */
    @Test
    @DisplayName("PreTool fires before the tool and PostTool after it, both naming the dispatched tool")
    void toolHooksBracketTheDispatch() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptReadThenAnswer(sessionId);
        node.hookRegistry().register(HookEventType.PRE_TOOL, ctx -> record("pre:" + ctx.getCurrentToolUse().getName()));
        node.hookRegistry().register(HookEventType.POST_TOOL, ctx -> record("post:" + ctx.getToolUse().getName() + ":"
                + (ctx.getCurrentToolUseResult().isError() ? "error" : "ok")));

        final OrcaAgentExecutionResult result = node.run(sessionId, "read the note");

        assertThat(result.isSuccess()).isTrue();
        assertThat(fired).containsExactly("pre:Read", "post:Read:ok");
    }

    /**
     * A block stops the tool. Asserted where it counts — the file the {@code Write} would have created does not exist —
     * rather than by observing that a hook returned a blocked result, which proves nothing about what happened next.
     *
     * <p>
     * The model is told, so the turn can react rather than silently losing an action, and {@code PostTool} still fires:
     * an audit hook must see the blocked dispatch too.
     */
    @Test
    @DisplayName("a blocking PreTool hook prevents the tool from running and the model is told why")
    void aBlockingPreToolHookStopsTheToolAndReachesTheModel() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("Write", Map.of("file_path", "blocked.txt", "content", "should not land")),
                ScriptedLlmClient.text("acknowledged"));
        node.hookRegistry().register(HookEventType.PRE_TOOL, ctx -> HookResult.block("writes are not allowed here"));
        node.hookRegistry().register(HookEventType.POST_TOOL, ctx -> record("post:" + ctx.getToolUse().getName()));

        final OrcaAgentExecutionResult result = node.run(sessionId, "write a file");

        assertThat(result.isSuccess()).isTrue();
        assertThat(node.fileExists("blocked.txt")).isFalse();
        assertThat(llm.lastCallFor(sessionId.value()).lastObservation())
                .isEqualTo("Tool blocked by hook: writes are not allowed here");
        assertThat(fired).containsExactly("post:Write");
    }

    /**
     * A rewritten input is what the tool acts on. Redirecting the path is the sharpest available form of the claim: the
     * file lands where the hook said and nowhere else, so the tool cannot have been handed the model's original input.
     */
    @Test
    @DisplayName("a PreTool hook that rewrites the input redirects what the tool actually does")
    void aPreToolHookCanRewriteTheToolInput() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("Write", Map.of("file_path", "requested.txt", "content", "payload")),
                ScriptedLlmClient.text("written"));
        node.hookRegistry().register(HookEventType.PRE_TOOL, ctx -> HookResult.withUpdatedInput(ToolInput.of(
                Map.of("file_path", "redirected.txt", "content", ctx.getCurrentToolUse().getInput().get("content")))));

        final OrcaAgentExecutionResult result = node.run(sessionId, "write a file");

        assertThat(result.isSuccess()).isTrue();
        assertThat(node.fileExists("redirected.txt")).isTrue();
        assertThat(node.readFile("redirected.txt")).isEqualTo("payload");
        assertThat(node.fileExists("requested.txt")).isFalse();
    }

    /**
     * A rewritten output is what the model sees — and only that. Both halves are asserted: the observation carried on
     * the next model call is the hook's text, and the file on disk still holds the original, proving the rewrite
     * happened on the observation path rather than by changing what the tool did.
     */
    @Test
    @DisplayName("a PostTool hook that rewrites the output changes the observation the model receives")
    void aPostToolHookCanRewriteTheObservation() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptReadThenAnswer(sessionId);
        node.hookRegistry().register(HookEventType.POST_TOOL,
                ctx -> HookResult.withUpdatedOutput(ToolResult.success("[redacted]")));

        final OrcaAgentExecutionResult result = node.run(sessionId, "read the note");

        assertThat(result.isSuccess()).isTrue();
        assertThat(llm.lastCallFor(sessionId.value()).lastObservation()).isEqualTo("[redacted]");
        assertThat(node.readFile(NOTE)).isEqualTo("seeded");
    }
}
