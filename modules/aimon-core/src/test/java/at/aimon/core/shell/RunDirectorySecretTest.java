package at.aimon.core.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RunDirectory — secret files")
class RunDirectorySecretTest {

    @Test
    @DisplayName("a secret file is a file of the directory marked secret; others are not")
    void secretMark() {
        final RunDirectory run = RunDirectory.builder().variable("D").file("a.py", new byte[]{1})
                .secretFile("key", new byte[]{2, 3}).build();
        assertThat(run.getPaths()).containsExactly("a.py", "key");
        assertThat(run.getSecretPaths()).containsExactly("key");
        assertThat(run.isSecret("key")).isTrue();
        assertThat(run.isSecret("a.py")).isFalse();
        assertThat(run.getContent("key")).containsExactly(2, 3);
        assertThat(run.toString()).contains("secrets=1").doesNotContain("\u0002");
        assertThatThrownBy(() -> run.isSecret("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a refused secret file leaves no mark")
    void refusedSecret() {
        final RunDirectory.Builder builder = RunDirectory.builder().variable("D").file("key", new byte[0]);
        assertThatThrownBy(() -> builder.secretFile("key", new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.build().getSecretPaths()).isEmpty();
    }
}
