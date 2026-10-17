package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.quota.QuotaKey;
import at.aimon.core.agent.quota.QuotaLimit;
import at.aimon.core.agent.quota.QuotaScope;
import at.aimon.core.agent.quota.QuotaUsage;
import at.aimon.core.agent.quota.QuotaViolation;
import at.aimon.core.agent.quota.QuotaWindow;
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

    @Test
    @DisplayName("a result built by a factory carries no execution id")
    void factoryResultHasNoExecutionId() {
        assertThat(SubagentExecutionResult.emptySuccess("done", Instant.now()).getExecutionId()).isEmpty();
        assertThat(SubagentExecutionResult.emptyFailure("nope", Instant.now()).getExecutionId()).isEmpty();
    }

    @Test
    @DisplayName("withExecutionId and withQuotaViolation each keep what the other set, in either order")
    void executionIdAndQuotaViolationPreserveEachOther() {
        final SubagentExecutionResult base = SubagentExecutionResult.emptyFailure("quota", Instant.now());
        final ExecutionId id = ExecutionId.of("subagent:explorer:1");
        final QuotaViolation violation = violation();

        final SubagentExecutionResult idFirst = base.withExecutionId(id).withQuotaViolation(violation);
        final SubagentExecutionResult quotaFirst = base.withQuotaViolation(violation).withExecutionId(id);

        assertThat(idFirst.getExecutionId()).contains(id);
        assertThat(idFirst.getQuotaViolation()).contains(violation);
        assertThat(quotaFirst.getExecutionId()).contains(id);
        assertThat(quotaFirst.getQuotaViolation()).contains(violation);
        assertThat(idFirst).isEqualTo(quotaFirst).hasSameHashCodeAs(quotaFirst);
        assertThat(base.getExecutionId()).isEmpty(); // the original is unchanged
        assertThat(idFirst.toString()).contains("subagent:explorer:1");
    }

    @Test
    @DisplayName("equals and hashCode distinguish results by execution id")
    void equalityIncludesExecutionId() {
        final SubagentExecutionResult base = SubagentExecutionResult.emptySuccess("done", Instant.now());
        final SubagentExecutionResult one = base.withExecutionId(ExecutionId.of("a"));

        assertThat(one).isNotEqualTo(base).isNotEqualTo(base.withExecutionId(ExecutionId.of("b")));
        assertThat(one).isEqualTo(base.withExecutionId(ExecutionId.of("a")))
                .hasSameHashCodeAs(base.withExecutionId(ExecutionId.of("a")));
        assertThatThrownBy(() -> base.withExecutionId(null)).isInstanceOf(NullPointerException.class);
    }

    private static QuotaViolation violation() {
        final QuotaKey key = QuotaKey.of(QuotaScope.tenant("acme"), QuotaWindow.DAILY, "2026-10-17");
        return QuotaViolation.of(key, QuotaLimit.daily().maxTokens(10).build(), QuotaUsage.of(42, Money.zeroUsd()));
    }
}
