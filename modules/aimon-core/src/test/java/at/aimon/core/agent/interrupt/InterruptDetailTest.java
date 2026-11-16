package at.aimon.core.agent.interrupt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InterruptDetail.normalize")
class InterruptDetailTest {

    @Test
    @DisplayName("null and blank mean no detail")
    void nullAndBlankAreEmpty() {
        assertThat(InterruptDetail.normalize(null)).isEmpty();
        assertThat(InterruptDetail.normalize("")).isEmpty();
        assertThat(InterruptDetail.normalize(" \n\t ")).isEmpty();
    }

    @Test
    @DisplayName("surrounding whitespace is stripped and inner text kept")
    void stripsSurroundingWhitespace() {
        assertThat(InterruptDetail.normalize("  deploy freeze  ")).contains("deploy freeze");
    }

    @Test
    @DisplayName("control characters become spaces, so a detail cannot forge a log line")
    void controlCharactersBecomeSpaces() {
        assertThat(InterruptDetail.normalize("a\r\nINFO forged\u0000b")).contains("a  INFO forged b");
    }

    @Test
    @DisplayName("a detail at the limit is kept whole")
    void atLimitKeptWhole() {
        final String atLimit = "x".repeat(InterruptDetail.MAX_LENGTH);

        assertThat(InterruptDetail.normalize(atLimit)).contains(atLimit);
    }

    @Test
    @DisplayName("a longer detail is cut to the limit and ends with an ellipsis")
    void overLimitIsCut() {
        final String normalized = InterruptDetail.normalize("x".repeat(InterruptDetail.MAX_LENGTH + 50)).orElseThrow();

        assertThat(normalized).hasSize(InterruptDetail.MAX_LENGTH).endsWith("…");
    }

    @Test
    @DisplayName("the cut never splits a surrogate pair")
    void cutKeepsSurrogatePairsWhole() {
        // The pair straddles the cut point: its high half is the last char that would be kept.
        final String raw = "x".repeat(InterruptDetail.MAX_LENGTH - 2) + "😀" + "tail";

        final String normalized = InterruptDetail.normalize(raw).orElseThrow();

        assertThat(normalized).endsWith("x…");
        assertThat(normalized.length()).isLessThanOrEqualTo(InterruptDetail.MAX_LENGTH);
    }

    @Test
    @DisplayName("Unicode line/paragraph separators and invisible format characters become spaces")
    void separatorsAndFormatCharactersBecomeSpaces() {
        // U+2028/U+2029 render as line breaks in some viewers; U+202E reorders text; U+200B is invisible.
        assertThat(InterruptDetail.normalize("a\u2028b\u2029c\u202Ed\u200Be")).contains("a b c d e");
    }

    @Test
    @DisplayName("normalising a normalised value returns it unchanged — plain, cut, and cut before a surrogate pair")
    void normalizeIsIdempotent() {
        final String[] raws = {"  deploy\nfreeze  ", "x".repeat(InterruptDetail.MAX_LENGTH + 50),
                "x".repeat(InterruptDetail.MAX_LENGTH - 2) + "\uD83D\uDE00tail"};
        for (String raw : raws) {
            final String once = InterruptDetail.normalize(raw).orElseThrow();

            assertThat(InterruptDetail.normalize(once)).as("re-normalising %s", once).contains(once);
        }
    }
}
