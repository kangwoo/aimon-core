package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;

@DisplayName("ToolContextEnrichmentInfo")
class ToolContextEnrichmentInfoTest {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.of("agent:test-1");

    @Test
    @DisplayName("the invoking conversation is optional and absent by default")
    void invokingConversationIsAbsentByDefault() {
        ToolContextEnrichmentInfo info = ToolContextEnrichmentInfo.builder().sessionId(SessionId.of("conv-1"))
                .agentRuntimeId(RUNTIME).build();

        assertThat(info.getInvokingSessionId()).isEmpty();
        assertThat(info.getSessionId()).contains(SessionId.of("conv-1"));
    }

    @Test
    @DisplayName("keeps the run's own id and the invoking session distinct")
    void keepsBothConversationIds() {
        ToolContextEnrichmentInfo info = ToolContextEnrichmentInfo.builder()
                .executionId(ExecutionId.of("subagent:reviewer:run-1")).invokingSessionId(SessionId.of("conv-1"))
                .agentRuntimeId(RUNTIME).build();

        assertThat(info.getExecutionId()).contains(ExecutionId.of("subagent:reviewer:run-1"));
        assertThat(info.getInvokingSessionId()).contains(SessionId.of("conv-1"));
    }

    /**
     * Stage 6-4 — the session id stopped being mandatory. A fork has no session, and the only way it could satisfy a
     * required field was to invent one; an enricher then had no way to tell that invention from an id the user was
     * actually behind.
     */
    @Test
    @DisplayName("describes a run with no session by its execution id, leaving the session id empty")
    void aRunWithoutASessionReportsAnExecutionIdInstead() {
        ToolContextEnrichmentInfo info = ToolContextEnrichmentInfo.builder()
                .executionId(ExecutionId.of("subagent:reviewer:run-1")).agentRuntimeId(RUNTIME).build();

        assertThat(info.getSessionId()).isEmpty();
        assertThat(info.getExecutionId()).contains(ExecutionId.of("subagent:reviewer:run-1"));
        assertThat(info.getInvokingSessionId()).isEmpty();
    }

    /**
     * A session's turn reports the session and nothing else: an execution id would be a second name for something
     * already named.
     */
    @Test
    @DisplayName("a session's turn carries no execution id")
    void aSessionTurnCarriesNoExecutionId() {
        ToolContextEnrichmentInfo info = ToolContextEnrichmentInfo.builder().sessionId(SessionId.of("conv-1"))
                .agentRuntimeId(RUNTIME).build();

        assertThat(info.getExecutionId()).isEmpty();
    }

    /**
     * The pair is deliberately not cross-validated — same shape as the Stage 6-2 hook contexts. Nothing in this type
     * knows enough to rule out a caller that legitimately has neither, and rejecting the empty pair is exactly what
     * pushes a caller into fabricating an id.
     */
    @Test
    @DisplayName("accepts neither id without complaint, and never both as a validation error")
    void doesNotCrossValidateTheIdPair() {
        ToolContextEnrichmentInfo neither = ToolContextEnrichmentInfo.builder().agentRuntimeId(RUNTIME).build();
        assertThat(neither.getSessionId()).isEmpty();
        assertThat(neither.getExecutionId()).isEmpty();

        ToolContextEnrichmentInfo both = ToolContextEnrichmentInfo.builder().sessionId(SessionId.of("conv-1"))
                .executionId(ExecutionId.of("run-1")).agentRuntimeId(RUNTIME).build();
        assertThat(both.getSessionId()).contains(SessionId.of("conv-1"));
        assertThat(both.getExecutionId()).contains(ExecutionId.of("run-1"));
    }

    @Test
    @DisplayName("rejects a missing agent runtime id")
    void rejectsMissingRequiredIds() {
        assertThatThrownBy(() -> ToolContextEnrichmentInfo.builder().sessionId(SessionId.of("conv-1")).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("agentRuntimeId");
    }
}
