package at.aimon.core.memory.deriver.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.redaction.DefaultRedactionPolicy;

@DisplayName("DeriverMemorySearchTool")
class DeriverMemorySearchToolTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView AGENT = PeerView.of(WS, Principal.system());

    private InMemoryObservationStore store;
    private DeriverMemorySearchTool tool;

    @BeforeEach
    void setUp() {
        store = new InMemoryObservationStore();
        tool = new DeriverMemorySearchTool(store);
    }

    @Test
    @DisplayName("returns matching observations for the subject")
    void hits() {
        store.save(observation(ALICE, "alice prefers green tea", 0.92, ObservationType.EXPLICIT));
        store.save(observation(ALICE, "alice runs marathons", 0.7, ObservationType.DEDUCTIVE));

        ToolResult result = tool.execute(ToolInput.of(Map.of("query", "tea")), context(ALICE));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("deriver.memory.search results for", "alice prefers green tea",
                "confidence=0.92");
    }

    @Test
    @DisplayName("returns success with empty marker when nothing matches")
    void empty() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("query", "absent")), context(ALICE));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("hits: 0", "(no matching observations)");
    }

    @Test
    @DisplayName("blank query yields error")
    void blankQuery() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("query", "  ")), context(ALICE));

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("missing workspace yields error")
    void missingWorkspace() {
        ToolContext ctx = ToolContext.builder().put(DeriverMemorySearchTool.OBSERVER_KEY, ALICE).build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("query", "tea")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("memory.workspace");
    }

    @Test
    @DisplayName("missing observer yields error")
    void missingObserver() {
        ToolContext ctx = ToolContext.builder().put(DeriverMemorySearchTool.WORKSPACE_KEY, WS).build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("query", "tea")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("memory.observer");
    }

    @Test
    @DisplayName("subject in a different workspace yields error")
    void crossWorkspaceRejected() {
        Workspace ws2 = Workspace.builder().id("ws-2").build();
        PeerView aliceWs2 = PeerView.of(ws2, Principal.user("alice", "Alice"));
        ToolContext ctx = ToolContext.builder().put(DeriverMemorySearchTool.WORKSPACE_KEY, WS)
                .put(DeriverMemorySearchTool.OBSERVER_KEY, AGENT).put(DeriverMemorySearchTool.SUBJECT_KEY, aliceWs2)
                .build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("query", "tea")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("workspace");
    }

    @Test
    @DisplayName("redaction policy rewrites query before searching")
    void redactsQuery() {
        AtomicReference<String> seenQuery = new AtomicReference<>();
        ObservationStore spy = new InMemoryObservationStore() {
            @Override
            public List<Observation> semanticSearch(PeerView subject, String query, int topK) {
                seenQuery.set(query);
                return List.of();
            }
        };
        DeriverMemorySearchTool secured = new DeriverMemorySearchTool(spy, new DefaultRedactionPolicy());

        ToolResult result = secured.execute(ToolInput.of(Map.of("query", "look up AKIAIOSFODNN7EXAMPLE for alice")),
                context(ALICE));

        assertThat(result.isSuccess()).isTrue();
        assertThat(seenQuery.get()).contains("[REDACTED:AWS_KEY]");
        assertThat(seenQuery.get()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }

    @Test
    @DisplayName("top_k below 1 yields error")
    void topKTooSmall() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("query", "x", "top_k", 0)), context(ALICE));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("top_k");
    }

    @Test
    @DisplayName("top_k above MAX_TOP_K is clamped")
    void topKClamped() {
        for (int i = 0; i < 60; i++) {
            store.save(observation(ALICE, "fact " + i + " tea", 0.5, ObservationType.EXPLICIT));
        }

        ToolResult result = tool.execute(ToolInput.of(Map.of("query", "tea", "top_k", 999)), context(ALICE));

        assertThat(result.isSuccess()).isTrue();
        long lineHits = result.getContent().lines().filter(line -> line.startsWith("- [")).count();
        assertThat(lineHits).isLessThanOrEqualTo(DeriverMemorySearchTool.MAX_TOP_K);
    }

    private static ToolContext context(PeerView observer) {
        return ToolContext.builder().put(DeriverMemorySearchTool.WORKSPACE_KEY, WS)
                .put(DeriverMemorySearchTool.OBSERVER_KEY, observer).put(DeriverMemorySearchTool.SUBJECT_KEY, observer)
                .build();
    }

    private static Observation observation(PeerView subject, String content, double confidence, ObservationType type) {
        return Observation.builder().id(ObservationId.of(WS, "obs-" + Math.abs(content.hashCode()))).subject(subject)
                .observer(AGENT).content(content).type(type).confidence(confidence).createdAt(Instant.now()).build();
    }
}
