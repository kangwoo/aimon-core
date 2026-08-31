package at.aimon.core.agent.session.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.budget.CompletionReason;

/**
 * Contract of the wire-safe result projection, now that it is a published SPI type rather than three private copies.
 *
 * <p>
 * The invariants themselves are not new — they came along from the backend copies unchanged. What is new is that
 * {@code aimon-core} publishes them, and that the forwarded-turn rail is a second caller relying on the same
 * artifact-dropping promise (design §9 question 4) that idempotency replay already relied on.
 */
@DisplayName("StoredAgentExecutionResult projection")
class StoredAgentExecutionResultTest {

    @Test
    @DisplayName("from() keeps every wire-safe field and drops artifacts")
    void projectionDropsArtifacts() {
        final FileArtifact artifact = FileArtifact.builder().path("/out/report.csv").fileName("report.csv").size(12)
                .build();

        final StoredAgentExecutionResult projected = StoredAgentExecutionResult.from(new AgentExecutionResult() {
            @Override
            public boolean isSuccess() {
                return true;
            }

            @Override
            public String getFinalAnswer() {
                return "done";
            }

            @Override
            public String getErrorMessage() {
                return null;
            }

            @Override
            public List<FileArtifact> getArtifacts() {
                return List.of(artifact);
            }

            @Override
            public CompletionReason getCompletionReason() {
                return CompletionReason.MAX_ITERATIONS;
            }

            @Override
            public boolean wasStreamed() {
                return true;
            }
        });

        assertThat(projected.isSuccess()).isTrue();
        assertThat(projected.getFinalAnswer()).isEqualTo("done");
        assertThat(projected.getErrorMessage()).isNull();
        assertThat(projected.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
        assertThat(projected.wasStreamed()).isTrue();
        assertThat(projected.getArtifacts()).as("a result that crossed a node boundary carries no artifacts").isEmpty();
    }

    @Test
    @DisplayName("a failed result keeps its error message and needs no answer")
    void failedResultProjects() {
        final StoredAgentExecutionResult failed = StoredAgentExecutionResult.builder().success(false)
                .errorMessage("budget exhausted").completionReason(CompletionReason.TOKEN_BUDGET_EXCEEDED).build();

        assertThat(failed.isSuccess()).isFalse();
        assertThat(failed.getFinalAnswer()).isNull();
        assertThat(failed.getErrorMessage()).isEqualTo("budget exhausted");
        assertThat(failed.wasStreamed()).as("defaults to false").isFalse();
    }

    @Test
    @DisplayName("the builder refuses a result that would decode as neither a success nor a failure")
    void builderEnforcesTheOutcomeInvariant() {
        assertThatThrownBy(() -> StoredAgentExecutionResult.builder().success(true).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("finalAnswer");
        assertThatThrownBy(() -> StoredAgentExecutionResult.builder().success(false).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("errorMessage");
        assertThatThrownBy(() -> StoredAgentExecutionResult.builder().success(true).finalAnswer("x")
                .completionReason(null).build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("completionReason");
        assertThatThrownBy(() -> StoredAgentExecutionResult.from(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("completionReason defaults to COMPLETED so a minimal success is constructible")
    void completionReasonDefaults() {
        assertThat(StoredAgentExecutionResult.builder().success(true).finalAnswer("hi").build().getCompletionReason())
                .isEqualTo(CompletionReason.COMPLETED);
    }
}
