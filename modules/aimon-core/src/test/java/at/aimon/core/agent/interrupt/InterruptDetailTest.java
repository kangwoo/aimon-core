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

    @Test
    @DisplayName("invisible characters outside the BMP (tag characters) and unpaired surrogates become spaces")
    void supplementaryFormatCharactersAndLoneSurrogatesBecomeSpaces() {
        // U+E0041 U+E0042 are TAG LATIN CAPITAL A/B — invisible, and usable to smuggle hidden text.
        final String tags = new String(Character.toChars(0xE0041)) + new String(Character.toChars(0xE0042));

        assertThat(InterruptDetail.normalize("a" + tags + "b")).as("one space per code point").contains("a  b");
        assertThat(InterruptDetail.normalize("a\uD800b\uDC00c")).contains("a b c");
    }

    @Test
    @DisplayName("a visible character outside the BMP is kept whole")
    void supplementaryVisibleCharacterKept() {
        assertThat(InterruptDetail.normalize("stop \uD83D\uDED1 now")).contains("stop \uD83D\uDED1 now");
    }

    @Test
    @DisplayName("a huge input gives the same result as cutting the whole cleaned string, whitespace around the cut included")
    void boundedCopyKeepsTheSamePrefix() {
        final String body = "y".repeat(InterruptDetail.MAX_LENGTH - 3);
        // Leading whitespace, then a whitespace run straddling the cut point, then a long tail.
        final String raw = "   \n" + body + "  \t  " + "z".repeat(1_000_000);

        final String normalized = InterruptDetail.normalize(raw).orElseThrow();

        assertThat(normalized).hasSize(InterruptDetail.MAX_LENGTH).startsWith(body + "  ").endsWith("\u2026");
    }

    @Test
    @DisplayName("text that ends in whitespace just past the limit is kept whole when the strip brings it under")
    void trailingWhitespacePastLimitIsStripped() {
        final String body = "y".repeat(InterruptDetail.MAX_LENGTH);

        assertThat(InterruptDetail.normalize(body + "      ")).contains(body);
    }

    @Test
    @DisplayName("content after a whitespace run that crosses the limit still forces the cut and its ellipsis")
    void contentAfterWhitespaceAtTheLimitIsCut() {
        final String body = "x".repeat(InterruptDetail.MAX_LENGTH - 2);

        final String normalized = InterruptDetail.normalize(body + " ".repeat(10) + "y").orElseThrow();

        assertThat(normalized).isEqualTo(body + " \u2026");
    }

    @Test
    @DisplayName("a huge whitespace tail is stripped, and is not copied to find that out")
    void hugeWhitespaceTailIsStripped() {
        final String body = "x".repeat(InterruptDetail.MAX_LENGTH);

        assertThat(InterruptDetail.normalize(body + "\u3000".repeat(5_000_000))).contains(body);
    }

    @Test
    @DisplayName("the bounded copy gives exactly what a full pass — map, strip, cut — gives, on random input")
    void boundedCopyMatchesAFullPass() {
        final int[] pool = {'a', 'b', ' ', '\t', '\n', 0x1C, 0x85, 0xA0, 0x1680, 0x2000, 0x2028, 0x200B, 0x202E, 0xFEFF,
                0x3000, 0xD800, 0xDC00, 0x1F600, 0xE0041, 0x2026};
        final java.util.Random random = new java.util.Random(363);
        for (int round = 0; round < 20_000; round++) {
            final StringBuilder raw = new StringBuilder();
            final int length = 480 + random.nextInt(80);
            for (int i = 0; i < length; i++) {
                raw.appendCodePoint(pool[random.nextInt(pool.length)]);
            }
            final String input = raw.toString();

            assertThat(InterruptDetail.normalize(input)).as("input #%d", round).isEqualTo(fullPass(input));
        }
    }

    /** The reference: clean every code point, strip, then cut — no early exit. */
    private static java.util.Optional<String> fullPass(String raw) {
        final StringBuilder sb = new StringBuilder();
        raw.codePoints().forEach(cp -> {
            final int type = Character.getType(cp);
            final boolean unsafe = Character.isISOControl(cp) || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.FORMAT || type == Character.SURROGATE;
            sb.appendCodePoint(unsafe ? ' ' : cp);
        });
        final String flat = sb.toString().strip();
        if (flat.isEmpty()) {
            return java.util.Optional.empty();
        }
        if (flat.length() <= InterruptDetail.MAX_LENGTH) {
            return java.util.Optional.of(flat);
        }
        int cut = InterruptDetail.MAX_LENGTH - 1;
        if (Character.isLowSurrogate(flat.charAt(cut)) && Character.isHighSurrogate(flat.charAt(cut - 1))) {
            cut--;
        }
        return java.util.Optional.of(flat.substring(0, cut) + "\u2026");
    }
}
