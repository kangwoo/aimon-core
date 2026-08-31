package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.cost.Money;

@DisplayName("SubagentExecutionResult Tests")
class SubagentExecutionResultTest {

    @Test
    @DisplayName("emptySuccess produces a success with an empty snapshot, zero-cost metadata, and zeroUsd cost")
    void emptySuccessShape() {
        SubagentExecutionResult result = SubagentExecutionResult.emptySuccess("done", Instant.now());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(result.getConversationHistory()).isEmpty();
        assertThat(result.getIterationCount()).isZero();
        assertThat(result.getMetadata().getTokenUsage().getTotalTokens()).isZero();
        assertThat(result.getCost()).isEqualTo(Money.zeroUsd()); // cost defaults to zero when not priced
    }

    @Test
    @DisplayName("emptyFailure produces a failure with an empty snapshot and zero-cost metadata")
    void emptyFailureShape() {
        SubagentExecutionResult result = SubagentExecutionResult.emptyFailure("nope", Instant.now());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("nope");
        assertThat(result.getConversationHistory()).isEmpty();
        assertThat(result.getIterationCount()).isZero();
        assertThat(result.getMetadata().getTokenUsage().getTotalTokens()).isZero();
    }

    @Test
    @DisplayName("emptySuccess rejects a null final answer and a null start time")
    void emptySuccessRejectsNulls() {
        assertThatThrownBy(() -> SubagentExecutionResult.emptySuccess(null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SubagentExecutionResult.emptySuccess("x", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("emptyFailure rejects a null error message and a null start time")
    void emptyFailureRejectsNulls() {
        assertThatThrownBy(() -> SubagentExecutionResult.emptyFailure(null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SubagentExecutionResult.emptyFailure("x", null))
                .isInstanceOf(NullPointerException.class);
    }
}
