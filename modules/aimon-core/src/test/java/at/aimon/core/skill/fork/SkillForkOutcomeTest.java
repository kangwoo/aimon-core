package at.aimon.core.skill.fork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SkillForkOutcomeTest {

    @Test
    void success_CarriesFinalAnswer() {
        SkillForkOutcome outcome = SkillForkOutcome.success("done");

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.getFinalAnswer()).contains("done");
        assertThat(outcome.getErrorMessage()).isEmpty();
    }

    @Test
    void failure_CarriesErrorMessage() {
        SkillForkOutcome outcome = SkillForkOutcome.failure("boom");

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.getFinalAnswer()).isEmpty();
        assertThat(outcome.getErrorMessage()).contains("boom");
    }

    @Test
    void success_NullThrows() {
        assertThatThrownBy(() -> SkillForkOutcome.success(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Final answer");
    }

    @Test
    void failure_NullThrows() {
        assertThatThrownBy(() -> SkillForkOutcome.failure(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Error message");
    }
}
