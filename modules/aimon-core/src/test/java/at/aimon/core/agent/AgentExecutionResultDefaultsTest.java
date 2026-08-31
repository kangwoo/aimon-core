package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.CompletionReason;

/**
 * Verifies the default behavior of {@link AgentExecutionResult#getCompletionReason()} for implementations that predate
 * the method and therefore do not override it.
 */
@DisplayName("AgentExecutionResult default methods")
class AgentExecutionResultDefaultsTest {

    @Test
    @DisplayName("Successful legacy implementation reports COMPLETED by default")
    void defaultReasonForSuccess() {
        AgentExecutionResult result = new LegacyResult(true);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
    }

    @Test
    @DisplayName("Failed legacy implementation reports ERROR by default")
    void defaultReasonForFailure() {
        AgentExecutionResult result = new LegacyResult(false);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
    }

    private static final class LegacyResult implements AgentExecutionResult {
        private final boolean success;

        LegacyResult(boolean success) {
            this.success = success;
        }

        @Override
        public boolean isSuccess() {
            return success;
        }

        @Override
        public String getFinalAnswer() {
            return success ? "ok" : null;
        }

        @Override
        public String getErrorMessage() {
            return success ? null : "fail";
        }
    }
}
