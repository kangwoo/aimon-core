package at.aimon.core.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.dialectic.DialecticEngine;
import at.aimon.core.memory.dialectic.DialecticQuery;
import at.aimon.core.memory.dialectic.DialecticResponse;
import at.aimon.core.memory.dialectic.ReasoningLevel;

@DisplayName("MemoryChatTool")
class MemoryChatToolTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice"));
    private static final PeerView BOB = PeerView.of(WS, Principal.user("bob"));

    private CapturingDialecticEngine engine;
    private MemoryChatTool tool;

    @BeforeEach
    void setUp() {
        engine = new CapturingDialecticEngine();
        tool = new MemoryChatTool(engine);
    }

    @Test
    @DisplayName("returns the dialectic answer on success")
    void happyPath() {
        engine.respond(DialecticResponse.builder().answer("Alice prefers tea.")
                .observationsConsidered(java.util.List.of()).tokenUsage(TokenUsage.of(10, 5, 15)).build());

        ToolResult result = tool.execute(ToolInput.of(Map.of("question", "What does Alice drink?")),
                contextWith(ALICE, BOB));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("Alice prefers tea.");
        DialecticQuery captured = engine.lastQuery.get();
        assertThat(captured.getQuestion()).isEqualTo("What does Alice drink?");
        assertThat(captured.getObserver()).isEqualTo(ALICE);
        assertThat(captured.getSubject()).isEqualTo(BOB);
        assertThat(captured.getLevel()).isEqualTo(ReasoningLevel.BALANCED);
    }

    @Test
    @DisplayName("subject defaults to observer when SUBJECT_KEY is absent")
    void subjectDefaultsToObserver() {
        engine.respond(DialecticResponse.text("ok"));
        ToolContext ctx = ToolContext.builder().put(MemoryChatTool.WORKSPACE_KEY, WS)
                .put(MemoryChatTool.OBSERVER_KEY, ALICE).build();

        tool.execute(ToolInput.of(Map.of("question", "?")), ctx);

        assertThat(engine.lastQuery.get().getSubject()).isEqualTo(ALICE);
    }

    @Test
    @DisplayName("level parameter is parsed case-insensitively")
    void levelParsed() {
        engine.respond(DialecticResponse.text("ok"));

        tool.execute(ToolInput.of(Map.of("question", "?", "level", "deep")), contextWith(ALICE, ALICE));

        assertThat(engine.lastQuery.get().getLevel()).isEqualTo(ReasoningLevel.DEEP);
    }

    @Test
    @DisplayName("unknown level value yields error result, not exception")
    void unknownLevelIsError() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("question", "?", "level", "BANANA")),
                contextWith(ALICE, ALICE));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("BANANA");
    }

    @Test
    @DisplayName("blank question yields error result")
    void blankQuestionIsError() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("question", "   ")), contextWith(ALICE, ALICE));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("question");
    }

    @Test
    @DisplayName("missing workspace yields error result")
    void missingWorkspaceIsError() {
        ToolContext ctx = ToolContext.builder().put(MemoryChatTool.OBSERVER_KEY, ALICE).build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("question", "?")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("memory.workspace");
    }

    @Test
    @DisplayName("missing observer yields error result")
    void missingObserverIsError() {
        ToolContext ctx = ToolContext.builder().put(MemoryChatTool.WORKSPACE_KEY, WS).build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("question", "?")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("memory.observer");
    }

    @Test
    @DisplayName("sessionId is propagated when present in context")
    void sessionIdPropagated() {
        engine.respond(DialecticResponse.text("ok"));
        ToolContext ctx = ToolContext.builder().put(MemoryChatTool.WORKSPACE_KEY, WS)
                .put(MemoryChatTool.OBSERVER_KEY, ALICE).put(MemoryChatTool.SESSION_ID_KEY, "sess-7").build();

        tool.execute(ToolInput.of(Map.of("question", "?")), ctx);

        assertThat(engine.lastQuery.get().getSessionId()).contains("sess-7");
    }

    @Test
    @DisplayName("engine throwing is swallowed and surfaced as error result")
    void engineExceptionSwallowed() {
        engine.respondThrowing(new RuntimeException("boom"));

        ToolResult result = tool.execute(ToolInput.of(Map.of("question", "?")), contextWith(ALICE, ALICE));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("boom");
    }

    private ToolContext contextWith(PeerView observer, PeerView subject) {
        return ToolContext.builder().put(MemoryChatTool.WORKSPACE_KEY, WS).put(MemoryChatTool.OBSERVER_KEY, observer)
                .put(MemoryChatTool.SUBJECT_KEY, subject).build();
    }

    private static final class CapturingDialecticEngine implements DialecticEngine {
        private DialecticResponse response = DialecticResponse.text("ok");
        private RuntimeException error;
        private final AtomicReference<DialecticQuery> lastQuery = new AtomicReference<>();

        void respond(DialecticResponse response) {
            this.response = response;
            this.error = null;
        }

        void respondThrowing(RuntimeException error) {
            this.error = error;
        }

        @Override
        public DialecticResponse query(DialecticQuery q) {
            lastQuery.set(q);
            if (error != null) {
                throw error;
            }
            return response;
        }
    }
}
