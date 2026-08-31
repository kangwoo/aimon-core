package at.aimon.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WorkflowBudget — agent-count + optional aggregate token ceilings")
class WorkflowBudgetTest {

    @Test
    @DisplayName("defaults() caps agents and leaves tokens unlimited")
    void defaultsCapAgentsOnly() {
        final WorkflowBudget budget = WorkflowBudget.defaults();

        assertThat(budget.getMaxAgents()).isEqualTo(WorkflowBudget.DEFAULT_MAX_AGENTS);
        assertThat(budget.hasTokenLimit()).isFalse();
    }

    @Test
    @DisplayName("ofAgents sets the agent ceiling, tokens unlimited")
    void ofAgents() {
        final WorkflowBudget budget = WorkflowBudget.ofAgents(5);

        assertThat(budget.getMaxAgents()).isEqualTo(5);
        assertThat(budget.hasTokenLimit()).isFalse();
    }

    @Test
    @DisplayName("of(maxAgents, maxTokens) sets both ceilings")
    void ofAgentsAndTokens() {
        final WorkflowBudget budget = WorkflowBudget.of(3, 1000);

        assertThat(budget.getMaxAgents()).isEqualTo(3);
        assertThat(budget.getMaxTokens()).isEqualTo(1000);
        assertThat(budget.hasTokenLimit()).isTrue();
    }

    @Test
    @DisplayName("a non-positive token limit means unlimited")
    void nonPositiveTokenLimitIsUnlimited() {
        assertThat(WorkflowBudget.of(3, 0).hasTokenLimit()).isFalse();
        assertThat(WorkflowBudget.of(3, -1).hasTokenLimit()).isFalse();
    }

    @Test
    @DisplayName("rejects maxAgents < 1")
    void rejectsMaxAgentsBelowOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkflowBudget.ofAgents(0));
        assertThatIllegalArgumentException().isThrownBy(() -> WorkflowBudget.of(0, 100));
    }

    @Test
    @DisplayName("of(agents, tokens, costUsd) sets the cost ceiling in micros; a non-positive cost means unlimited")
    void costCeiling() {
        final WorkflowBudget b = WorkflowBudget.of(10, 500, 0.0025);
        assertThat(b.hasCostLimit()).isTrue();
        assertThat(b.getMaxCostMicros()).isEqualTo(2500);

        assertThat(WorkflowBudget.defaults().hasCostLimit()).isFalse();
        assertThat(WorkflowBudget.of(10, 500).hasCostLimit()).isFalse();
        assertThat(WorkflowBudget.of(10, 500, 0).hasCostLimit()).isFalse();
        assertThat(WorkflowBudget.of(10, 500, -1).hasCostLimit()).isFalse();
    }
}
