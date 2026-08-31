package at.aimon.core.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextEnrichmentInfo;
import at.aimon.core.base.Principal;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("MemoryToolContextEnricher")
class MemoryToolContextEnricherTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice", "Alice"));

    @Test
    @DisplayName("populates workspace, observer, subject, and session id from conversation")
    void populatesAllKeys() {
        MemoryToolContextEnricher enricher = new MemoryToolContextEnricher(WS, ALICE);
        ToolContext.Builder builder = ToolContext.builder();
        SessionId sessionId = SessionId.of("conv-42");
        ToolContextEnrichmentInfo info = ToolContextEnrichmentInfo.builder().sessionId(sessionId)
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).build();

        enricher.enrich(builder, info);
        ToolContext ctx = builder.build();

        assertThat(ctx.get(MemoryRecallTool.WORKSPACE_KEY)).contains(WS);
        assertThat(ctx.get(MemoryRecallTool.OBSERVER_KEY)).contains(ALICE);
        assertThat(ctx.get(MemoryRecallTool.SUBJECT_KEY)).contains(ALICE);
        assertThat(ctx.get(MemoryRecallTool.SESSION_ID_KEY)).contains("conv-42");
    }

    @Test
    @DisplayName("prefers the invoking conversation over the run's own")
    void prefersInvokingConversation() {
        MemoryToolContextEnricher enricher = new MemoryToolContextEnricher(WS, ALICE);
        ToolContext.Builder builder = ToolContext.builder();
        // A subagent fork: it has no session of its own, and nothing was ever written under its run id, so recall must
        // key on the session that spawned it.
        ToolContextEnrichmentInfo info = ToolContextEnrichmentInfo.builder()
                .executionId(ExecutionId.of("subagent:reviewer:fork-7")).invokingSessionId(SessionId.of("conv-42"))
                .agentRuntimeId(AgentRuntimeId.of("agent:test-3")).build();

        enricher.enrich(builder, info);

        assertThat(builder.build().get(MemoryRecallTool.SESSION_ID_KEY)).contains("conv-42");
    }

    /**
     * Stage 6-4 — a run with no session and no invoker (a scheduled routine, an uninvoked fork) gets no memory session
     * id. Absent, {@code MemoryRecall(mode=LOCAL)} matches local representations across sessions; the alternative was
     * the run's own identity dressed up as a session, which is guaranteed to match nothing.
     */
    @Test
    @DisplayName("publishes no session id when the run has neither a session nor an invoker")
    void omitsTheKeyWhenThereIsNoSessionAtAll() {
        MemoryToolContextEnricher enricher = new MemoryToolContextEnricher(WS, ALICE);
        ToolContext.Builder builder = ToolContext.builder();
        ToolContextEnrichmentInfo info = ToolContextEnrichmentInfo.builder()
                .executionId(ExecutionId.of("routine:nightly:run-1")).agentRuntimeId(AgentRuntimeId.of("agent:test-4"))
                .build();

        enricher.enrich(builder, info);
        ToolContext ctx = builder.build();

        assertThat(ctx.get(MemoryRecallTool.SESSION_ID_KEY)).isEmpty();
        assertThat(ctx.get(MemoryRecallTool.WORKSPACE_KEY)).as("the rest of the enrichment still applies").contains(WS);
        assertThat(ctx.get(MemoryRecallTool.OBSERVER_KEY)).contains(ALICE);
    }

    @Test
    @DisplayName("rejects observer from a different workspace at construction time")
    void rejectsCrossWorkspaceObserver() {
        Workspace otherWs = Workspace.builder().id("ws-2").build();
        PeerView otherAlice = PeerView.of(otherWs, Principal.user("alice", "Alice"));

        assertThatThrownBy(() -> new MemoryToolContextEnricher(WS, otherAlice))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workspace");
    }

    @Test
    @DisplayName("idempotent: multiple invocations leave the same final state")
    void idempotent() {
        MemoryToolContextEnricher enricher = new MemoryToolContextEnricher(WS, ALICE);
        ToolContextEnrichmentInfo info = ToolContextEnrichmentInfo.builder().sessionId(SessionId.of("c"))
                .agentRuntimeId(AgentRuntimeId.of("agent:test-2")).build();

        ToolContext.Builder builder = ToolContext.builder();
        enricher.enrich(builder, info);
        enricher.enrich(builder, info);

        ToolContext ctx = builder.build();
        assertThat(ctx.get(MemoryRecallTool.WORKSPACE_KEY)).contains(WS);
        assertThat(ctx.get(MemoryRecallTool.OBSERVER_KEY)).contains(ALICE);
    }
}
