package at.aimon.core.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RunDirectory (EE-109)")
class RunDirectoryTest {

    @Test
    @DisplayName("holds sorted confined paths, their bytes and their total")
    void holdsTheFiles() {
        RunDirectory run = RunDirectory.builder().variable("AIMON_HOOK_DIR")
                .file("lib/common.sh", "true".getBytes(StandardCharsets.UTF_8))
                .file("guard.sh", "exit 0".getBytes(StandardCharsets.UTF_8)).build();

        assertThat(run.getVariable()).isEqualTo("AIMON_HOOK_DIR");
        assertThat(run.getPaths()).containsExactly("guard.sh", "lib/common.sh");
        assertThat(run.getContent("guard.sh")).asString(StandardCharsets.UTF_8).isEqualTo("exit 0");
        assertThat(run.getTotalBytes()).isEqualTo(10);
    }

    @Test
    @DisplayName("an empty directory is allowed: it is still created")
    void emptyIsAllowed() {
        assertThat(RunDirectory.builder().variable("X").build().getPaths()).isEmpty();
    }

    @Test
    @DisplayName("bytes are copied in and out, so nothing outside can change what the shell places")
    void defensiveCopies() {
        byte[] bytes = {1, 2, 3};
        RunDirectory run = RunDirectory.builder().variable("X").file("f", bytes).build();
        bytes[0] = 9;
        run.getContent("f")[1] = 9;

        assertThat(run.getContent("f")).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> run.getPaths().add("g")).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/abs", "../up", "a/../b", "a/./b", "a//b", "a/", "back\\slash"})
    @DisplayName("a path that could leave the directory is refused")
    void unconfinedPathsAreRefused(String path) {
        assertThatThrownBy(() -> RunDirectory.builder().variable("X").file(path, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a path added twice is refused")
    void duplicateIsRefused() {
        RunDirectory.Builder builder = RunDirectory.builder().variable("X").file("a", new byte[0]);

        assertThatThrownBy(() -> builder.file("a", new byte[1])).isInstanceOf(IllegalArgumentException.class);
    }

    // --- what no shell could place (#283 F1, F3) ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(chars = {'\0', '\n', '\r', '\t', '\u001b', '\u001f', '\u007f', '\u0080', '\u009b', '\u009f'})
    @DisplayName("a path with a control character is refused, and the message shows it escaped, never raw")
    void controlCharactersAreRefused(char control) {
        final String path = "scripts/a" + control + "b.sh";
        final String hex = String.format("%04x", (int) control);

        assertThatThrownBy(() -> RunDirectory.builder().variable("X").file(path, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'scripts/a\\u" + hex + "b.sh' contains a control character")
                .hasMessageContaining("(U+" + hex.toUpperCase(Locale.ROOT) + ")")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(String.valueOf(control)));
    }

    @Test
    @DisplayName("a control character is reported as such even where the path would also leave the directory")
    void controlCharacterIsCheckedFirst() {
        assertThatThrownBy(() -> RunDirectory.builder().variable("X").file("../\u001b[31m", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("\\u001b")
                .hasMessageNotContaining("\u001b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "a~b", "caf\u00e9", "a\u00a0b", "a\u202eb", "a\u2028b", "\ud83d\ude00"})
    @DisplayName("characters just outside the ISO control set are placed as they are")
    void nonControlCharactersAreAccepted(String path) {
        assertThat(RunDirectory.builder().variable("X").file(path, new byte[0]).build().getPaths())
                .containsExactly(path);
    }

    @ParameterizedTest
    @CsvSource({"a, a/b", "a/b, a", "a, a/b/c", "a/b/c, a", "a/b, a/b/c", "a/b/c, a/b"})
    @DisplayName("a path that is both a file and the parent directory of another is refused, in either order")
    void fileAndDirectoryOfOneNameAreRefused(String first, String second) {
        final RunDirectory.Builder builder = RunDirectory.builder().variable("X").file(first, new byte[0]);
        final String parent = first.length() < second.length() ? first : second;
        final String child = first.length() < second.length() ? second : first;

        assertThatThrownBy(() -> builder.file(second, new byte[0])).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not placeable in a run directory: '" + parent
                        + "' is a file and also the parent directory of '" + child + "'");
    }

    @ParameterizedTest
    @CsvSource({"a.txt, a/b", "a-b, a/b", "ab, a/b", "a/b, a.txt", "a/b, ab", "b, a/b", "a/bc, a/b"})
    @DisplayName("names that only sort near a directory are not mistaken for it")
    void neighboursAreAccepted(String first, String second) {
        assertThat(RunDirectory.builder().variable("X").file(first, new byte[0]).file(second, new byte[0]).build()
                .getPaths()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("the cap is published and enforced: exactly MAX_TOTAL_BYTES is placed, one byte more is not")
    void totalIsCapped() {
        assertThat(RunDirectory.MAX_TOTAL_BYTES).isEqualTo(4L * 1024 * 1024);
        final int half = (int) (RunDirectory.MAX_TOTAL_BYTES / 2);
        final RunDirectory.Builder builder = RunDirectory.builder().variable("X").file("a", new byte[half]).file("b",
                new byte[half]);

        assertThatThrownBy(() -> builder.file("c", new byte[1])).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'c' would make it hold " + (RunDirectory.MAX_TOTAL_BYTES + 1) + " bytes")
                .hasMessageContaining(RunDirectory.MAX_TOTAL_BYTES + "-byte limit");
        assertThat(builder.file("d", new byte[0]).build().getTotalBytes()).isEqualTo(RunDirectory.MAX_TOTAL_BYTES);
    }

    @Test
    @DisplayName("a refused file leaves the builder as it was")
    void refusalLeavesTheBuilderUnchanged() {
        final RunDirectory.Builder builder = RunDirectory.builder().variable("X").file("lib", new byte[3]);
        assertThatThrownBy(() -> builder.file("lib/x.py", new byte[5])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.file("big", new byte[(int) RunDirectory.MAX_TOTAL_BYTES]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.file("bad\nname", new byte[7])).isInstanceOf(IllegalArgumentException.class);

        final RunDirectory run = builder.file("other", new byte[(int) RunDirectory.MAX_TOTAL_BYTES - 3]).build();

        assertThat(run.getPaths()).containsExactly("lib", "other");
        assertThat(run.getTotalBytes()).isEqualTo(RunDirectory.MAX_TOTAL_BYTES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1X", "A-B", "A B", "A=B"})
    @DisplayName("a name that is not an environment variable is refused")
    void badVariableIsRefused(String variable) {
        assertThatThrownBy(() -> RunDirectory.builder().variable(variable).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a missing variable is refused")
    void missingVariableIsRefused() {
        assertThatThrownBy(() -> RunDirectory.builder().build()).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("toString names the variable and the sizes, never the content")
    void toStringHidesContent() {
        RunDirectory run = RunDirectory.builder().variable("AIMON_HOOK_DIR")
                .file("guard.sh", "token=s3cret".getBytes(StandardCharsets.UTF_8)).build();

        assertThat(run.toString()).contains("AIMON_HOOK_DIR").contains("files=1").contains("totalBytes=12")
                .doesNotContain("s3cret").doesNotContain("guard.sh");
    }

    @Test
    @DisplayName("asking for a file it does not hold is an error, not null")
    void unknownPathIsAnError() {
        assertThatThrownBy(() -> RunDirectory.builder().variable("X").build().getContent("a"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
