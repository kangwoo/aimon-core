package at.aimon.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Verdict — adversarial-verify tally semantics (Phase 4 §4.4)")
class VerdictTest {

    @Test
    @DisplayName("tally: an exact refute/validate tie does NOT survive (fail-closed)")
    void tieDoesNotSurvive() {
        final Verdict v = Verdict.tally(List.of(true, true, false, false), 4, 1);

        assertThat(v.isSurvived()).isFalse();
        assertThat(v.isInconclusive()).isFalse();
        assertThat(v.getRefutations()).isEqualTo(2);
        assertThat(v.getValidations()).isEqualTo(2);
        assertThat(v.getVotes()).containsExactly(true, true, false, false);
    }

    @Test
    @DisplayName("tally: strictly fewer refutations than half survives; half or more does not")
    void strictMajorityBoundary() {
        // 1 of 3: 1 * 2 < 3 → survives.
        assertThat(Verdict.tally(List.of(true, false, false), 3, 1).isSurvived()).isTrue();
        // 2 of 3: 2 * 2 >= 3 → refuted.
        assertThat(Verdict.tally(List.of(true, true, false), 3, 1).isSurvived()).isFalse();
        // 1 of 2: an exact tie again → refuted (fail-closed).
        assertThat(Verdict.tally(List.of(true, false), 2, 1).isSurvived()).isFalse();
    }

    @Test
    @DisplayName("tally: quorum < 1 is rejected")
    void quorumBelowOneThrows() {
        assertThatThrownBy(() -> Verdict.tally(List.of(false), 1, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quorum must be >= 1");
    }

    @Test
    @DisplayName("tally: total < votes.size() is rejected")
    void totalBelowVotesThrows() {
        assertThatThrownBy(() -> Verdict.tally(List.of(false, true), 1, 1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total must be >= votes.size()");
    }

    @Test
    @DisplayName("tally: all-abstention (empty votes) is inconclusive, never decisive")
    void allAbstentionIsInconclusive() {
        final Verdict v = Verdict.tally(List.of(), 3, 1);

        assertThat(v.isInconclusive()).isTrue();
        assertThat(v.isSurvived()).isFalse();
        assertThat(v.getValidVotes()).isZero();
        assertThat(v.getRefutations()).isZero();
        assertThat(v.getValidations()).isZero();
        assertThat(v.getTotal()).isEqualTo(3);
        assertThat(v.getQuorum()).isEqualTo(1);
        assertThat(v.getVotes()).isEmpty();
    }
}
