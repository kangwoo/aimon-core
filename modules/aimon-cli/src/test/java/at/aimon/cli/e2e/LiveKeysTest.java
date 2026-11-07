package at.aimon.cli.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The key resolver of the real-model E2E, over a fake environment and fake keys. */
@DisplayName("LiveKeys")
class LiveKeysTest {

    private static final String FAKE = "fake-test-key-123";

    private final Map<String, String> env = new HashMap<>();

    private LiveKeys keys(Path home) {
        return new LiveKeys(env::get, home.toString());
    }

    @Test
    @DisplayName("a key file is read in place and trimmed of its trailing newline")
    void keyFileTrimmed(@TempDir Path dir) throws IOException {
        final Path file = Files.writeString(dir.resolve("key"), "  " + FAKE + "\n");
        env.put(LiveKeys.OPENAI_KEY_FILE, file.toString());

        assertThat(keys(dir).openAiKey()).contains(FAKE);
        assertThat(Files.readString(file)).as("the file is not touched").isEqualTo("  " + FAKE + "\n");
    }

    @Test
    @DisplayName("a leading ~/ is expanded with the user home")
    void tildeExpanded(@TempDir Path home) throws IOException {
        Files.writeString(home.resolve(".key"), FAKE);
        env.put(LiveKeys.OPENAI_KEY_FILE, "~/.key");

        assertThat(keys(home).openAiKey()).contains(FAKE);
    }

    @Test
    @DisplayName("OPENAI_KEY takes precedence over the key file")
    void directKeyWins(@TempDir Path dir) {
        env.put(LiveKeys.OPENAI_KEY, "direct-fake");
        env.put(LiveKeys.OPENAI_KEY_FILE, dir.resolve("missing").toString());

        assertThat(keys(dir).openAiKey()).contains("direct-fake");
    }

    @Test
    @DisplayName("blank values count as unset")
    void blankIsUnset(@TempDir Path dir) {
        env.put(LiveKeys.OPENAI_KEY, "  ");
        env.put(LiveKeys.OPENAI_KEY_FILE, "");
        env.put(LiveKeys.ANTHROPIC_KEY, " ");

        assertThat(keys(dir).openAiKey()).isEmpty();
        assertThat(keys(dir).anthropicKey()).isEmpty();
    }

    @Test
    @DisplayName("a set but missing key file fails, naming the variable and the path")
    void missingFileFails(@TempDir Path dir) {
        final Path missing = dir.resolve("nope");
        env.put(LiveKeys.OPENAI_KEY_FILE, missing.toString());

        assertThatIllegalStateException().isThrownBy(() -> keys(dir).openAiKey())
                .withMessageContaining(LiveKeys.OPENAI_KEY_FILE).withMessageContaining(missing.toString());
    }

    @Test
    @DisplayName("an empty key file fails without printing content")
    void emptyFileFails(@TempDir Path dir) throws IOException {
        final Path empty = Files.writeString(dir.resolve("empty"), "\n");
        env.put(LiveKeys.OPENAI_KEY_FILE, empty.toString());

        assertThatIllegalStateException().isThrownBy(() -> keys(dir).openAiKey()).withMessageContaining("empty");
    }

    @Test
    @DisplayName("ANTHROPIC_KEY, falling back to ANTHROPIC_API_KEY")
    void anthropicFallback(@TempDir Path dir) {
        env.put(LiveKeys.ANTHROPIC_API_KEY, "api-fake");
        assertThat(keys(dir).anthropicKey()).contains("api-fake");

        env.put(LiveKeys.ANTHROPIC_KEY, "key-fake");
        assertThat(keys(dir).anthropicKey()).contains("key-fake");
    }
}
