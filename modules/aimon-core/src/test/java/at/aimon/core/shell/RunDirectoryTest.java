package at.aimon.core.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
    @ValueSource(strings = {"", "/abs", "../up", "a/../b", "a/./b", "a//b", "a/", "back\\slash", "nul\0byte"})
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
