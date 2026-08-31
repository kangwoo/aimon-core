package at.aimon.core.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LoopTransition Tests")
class LoopTransitionTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("of(reason, iteration) has no note")
        void ofWithoutNote() {
            LoopTransition t = LoopTransition.of(LoopTransitionReason.NEXT_ITERATION, 2);

            assertThat(t.getReason()).isEqualTo(LoopTransitionReason.NEXT_ITERATION);
            assertThat(t.getIteration()).isEqualTo(2);
            assertThat(t.getNote()).isEmpty();
        }

        @Test
        @DisplayName("of(reason, iteration, note) carries the note")
        void ofWithNote() {
            LoopTransition t = LoopTransition.of(LoopTransitionReason.QUEUED_INPUT, 3, "2 queued input(s) drained");

            assertThat(t.getReason()).isEqualTo(LoopTransitionReason.QUEUED_INPUT);
            assertThat(t.getIteration()).isEqualTo(3);
            assertThat(t.getNote()).contains("2 queued input(s) drained");
        }

        @Test
        @DisplayName("null note is normalised to absent")
        void nullNoteIsAbsent() {
            assertThat(LoopTransition.of(LoopTransitionReason.NEXT_ITERATION, 2, null).getNote()).isEmpty();
        }

        @Test
        @DisplayName("blank note is normalised to absent")
        void blankNoteIsAbsent() {
            assertThat(LoopTransition.of(LoopTransitionReason.NEXT_ITERATION, 2, "   ").getNote()).isEmpty();
        }

        @Test
        @DisplayName("null reason is rejected")
        void nullReasonRejected() {
            assertThatThrownBy(() -> LoopTransition.of(null, 2)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("reason");
        }

        @Test
        @DisplayName("iteration below 1 is rejected")
        void iterationBelowOneRejected() {
            assertThatThrownBy(() -> LoopTransition.of(LoopTransitionReason.NEXT_ITERATION, 0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("iteration");
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equal reason, iteration and note are equal and hash alike")
        void equalsAndHashCode() {
            LoopTransition a = LoopTransition.of(LoopTransitionReason.BUDGET_COMPACT, 4, "note");
            LoopTransition b = LoopTransition.of(LoopTransitionReason.BUDGET_COMPACT, 4, "note");

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("a differing field breaks equality")
        void notEqualWhenFieldDiffers() {
            LoopTransition base = LoopTransition.of(LoopTransitionReason.NEXT_ITERATION, 2);

            assertThat(base).isNotEqualTo(LoopTransition.of(LoopTransitionReason.QUEUED_INPUT, 2));
            assertThat(base).isNotEqualTo(LoopTransition.of(LoopTransitionReason.NEXT_ITERATION, 3));
            assertThat(base).isNotEqualTo(LoopTransition.of(LoopTransitionReason.NEXT_ITERATION, 2, "note"));
        }

        @Test
        @DisplayName("a blank note equals a null note (both absent)")
        void blankNoteEqualsNullNote() {
            assertThat(LoopTransition.of(LoopTransitionReason.NEXT_ITERATION, 2, "  "))
                    .isEqualTo(LoopTransition.of(LoopTransitionReason.NEXT_ITERATION, 2, null));
        }

        @Test
        @DisplayName("toString includes reason and iteration, and the note only when present")
        void toStringContent() {
            assertThat(LoopTransition.of(LoopTransitionReason.NEXT_ITERATION, 2).toString()).contains("NEXT_ITERATION")
                    .contains("2").doesNotContain("note=");
            assertThat(LoopTransition.of(LoopTransitionReason.QUEUED_INPUT, 3, "drained").toString())
                    .contains("note=drained");
        }
    }
}
