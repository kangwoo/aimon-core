package at.aimon.core.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.memory.InMemoryRepresentationStore;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.Workspace;

@DisplayName("MemoryRecallTool")
class MemoryRecallToolTest {

    private static final Instant T0 = Instant.parse("2025-01-15T10:00:00Z");
    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView BOB = PeerView.of(WS, Principal.user("bob", "Bob"));

    private InMemoryRepresentationStore store;
    private MemoryRecallTool tool;

    @BeforeEach
    void setUp() {
        store = new InMemoryRepresentationStore();
        tool = MemoryRecallTool.overStore(store);
    }

    @Test
    @DisplayName("returns formatted summary when a global representation exists")
    void globalHit() {
        store.save(globalRep(ALICE, "Alice prefers tea.", T0, 12));

        ToolResult result = tool.execute(ToolInput.of(), contextWith(ALICE));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Snapshot for", "scope: global", "tokenCount: 12",
                "Alice prefers tea.");
    }

    @Test
    @DisplayName("returns success with miss message when no representation exists")
    void globalMiss() {
        ToolResult result = tool.execute(ToolInput.of(), contextWith(ALICE));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("No global snapshot");
    }

    @Test
    @DisplayName("subject defaults to observer when SUBJECT_KEY is absent")
    void subjectDefaultsToObserver() {
        store.save(globalRep(ALICE, "self portrait", T0, 5));

        ToolContext ctx = ToolContext.builder().put(MemoryRecallTool.WORKSPACE_KEY, WS)
                .put(MemoryRecallTool.OBSERVER_KEY, ALICE).build();

        ToolResult result = tool.execute(ToolInput.of(), ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("self portrait");
    }

    @Test
    @DisplayName("LOCAL mode picks the latest local representation matching observer + sessionId")
    void localHit() {
        store.save(localRep(ALICE, BOB, "sess-1", "old", T0, 10));
        store.save(localRep(ALICE, BOB, "sess-1", "newest", T0.plusSeconds(60), 10));
        store.save(localRep(ALICE, BOB, "sess-2", "other-session", T0.plusSeconds(120), 10));

        ToolContext ctx = ToolContext.builder().put(MemoryRecallTool.WORKSPACE_KEY, WS)
                .put(MemoryRecallTool.OBSERVER_KEY, BOB).put(MemoryRecallTool.SUBJECT_KEY, ALICE)
                .put(MemoryRecallTool.SESSION_ID_KEY, "sess-1").build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("mode", "LOCAL")), ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("scope: local", "newest");
    }

    @Test
    @DisplayName("LOCAL mode without observer yields error")
    void localRequiresObserver() {
        ToolContext ctx = ToolContext.builder().put(MemoryRecallTool.WORKSPACE_KEY, WS)
                .put(MemoryRecallTool.SUBJECT_KEY, ALICE).build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("mode", "LOCAL")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("memory.observer");
    }

    @Test
    @DisplayName("missing workspace yields error")
    void missingWorkspace() {
        ToolContext ctx = ToolContext.builder().put(MemoryRecallTool.OBSERVER_KEY, ALICE).build();

        ToolResult result = tool.execute(ToolInput.of(), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("memory.workspace");
    }

    @Test
    @DisplayName("subject in a different workspace yields error")
    void crossWorkspaceRejected() {
        Workspace ws2 = Workspace.builder().id("ws-2").build();
        PeerView aliceWs2 = PeerView.of(ws2, Principal.user("alice", "Alice"));

        ToolContext ctx = ToolContext.builder().put(MemoryRecallTool.WORKSPACE_KEY, WS)
                .put(MemoryRecallTool.SUBJECT_KEY, aliceWs2).put(MemoryRecallTool.OBSERVER_KEY, ALICE).build();

        ToolResult result = tool.execute(ToolInput.of(), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("workspace");
    }

    @Test
    @DisplayName("unknown mode yields error result, not exception")
    void unknownModeIsError() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("mode", "BANANA")), contextWith(ALICE));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("BANANA");
    }

    @Test
    @DisplayName("max_tokens budget drops observations when representation is over budget")
    void budgetDropsObservations() {
        Observation obs = sampleObservation();
        store.save(Representation.builder().subject(ALICE).observations(List.of(obs)).summary("brief").generatedAt(T0)
                .tokenCount(500).build());

        ToolResult result = tool.execute(ToolInput.of(Map.of("max_tokens", 100)), contextWith(ALICE));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("over budget=100", "observations omitted");
        assertThat(result.getContent()).doesNotContain(obs.getContent());
    }

    @Test
    @DisplayName("max_tokens budget keeps observations when within budget")
    void budgetKeepsObservations() {
        Observation obs = sampleObservation();
        store.save(Representation.builder().subject(ALICE).observations(List.of(obs)).summary("brief").generatedAt(T0)
                .tokenCount(50).build());

        ToolResult result = tool.execute(ToolInput.of(Map.of("max_tokens", 100)), contextWith(ALICE));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains(obs.getContent());
    }

    @Test
    @DisplayName("negative max_tokens yields error")
    void negativeBudgetIsError() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("max_tokens", -1)), contextWith(ALICE));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("max_tokens");
    }

    private ToolContext contextWith(PeerView observer) {
        return ToolContext.builder().put(MemoryRecallTool.WORKSPACE_KEY, WS)
                .put(MemoryRecallTool.OBSERVER_KEY, observer).put(MemoryRecallTool.SUBJECT_KEY, observer).build();
    }

    private static Representation globalRep(PeerView subject, String summary, Instant generatedAt, int tokenCount) {
        return Representation.builder().subject(subject).summary(summary).generatedAt(generatedAt)
                .tokenCount(tokenCount).build();
    }

    private static Representation localRep(PeerView subject, PeerView observer, String sessionId, String summary,
            Instant generatedAt, int tokenCount) {
        return Representation.builder().subject(subject).observer(observer).sessionId(sessionId).summary(summary)
                .generatedAt(generatedAt).tokenCount(tokenCount).build();
    }

    private static Observation sampleObservation() {
        return Observation.builder().id(ObservationId.of(WS, "o-1")).subject(ALICE)
                .observer(PeerView.of(WS, Principal.system())).content("alice likes tea").type(ObservationType.EXPLICIT)
                .createdAt(T0).confidence(0.8d).build();
    }
}
