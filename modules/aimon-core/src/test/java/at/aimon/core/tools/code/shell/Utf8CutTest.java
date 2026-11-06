package at.aimon.core.tools.code.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Utf8Cut — where a byte cut decodes without a broken character")
class Utf8CutTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"é", "한", "😀"})
    @DisplayName("a 2-, 3- or 4-byte character cut at every offset is dropped whole; uncut it is kept")
    void trailingCut(String character) {
        final byte[] bytes = ("ab" + character).getBytes(StandardCharsets.UTF_8);
        final int whole = bytes.length;
        assertThat(Utf8Cut.completeLength(bytes, whole)).isEqualTo(whole);
        for (int cut = 3; cut < whole; cut++) {
            assertThat(Utf8Cut.completeLength(bytes, cut)).as("cut at %d", cut).isEqualTo(2);
        }
        assertThat(Utf8Cut.completeLength(bytes, 2)).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"é", "한", "😀"})
    @DisplayName("a cut from the front skips the continuation bytes it left")
    void leadingCut(String character) {
        final byte[] bytes = (character + "z").getBytes(StandardCharsets.UTF_8);
        for (int from = 1; from < bytes.length - 1; from++) {
            final byte[] tail = java.util.Arrays.copyOfRange(bytes, from, bytes.length);
            assertThat(Utf8Cut.leadingContinuations(tail, tail.length)).as("from %d", from)
                    .isEqualTo(bytes.length - 1 - from);
        }
        assertThat(Utf8Cut.leadingContinuations(bytes, bytes.length)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", "plain ascii"})
    @DisplayName("ASCII and empty input are unchanged")
    void ascii(String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertThat(Utf8Cut.completeLength(bytes, bytes.length)).isEqualTo(bytes.length);
        assertThat(Utf8Cut.leadingContinuations(bytes, bytes.length)).isZero();
    }
}
