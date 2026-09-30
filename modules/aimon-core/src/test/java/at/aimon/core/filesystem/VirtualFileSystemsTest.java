package at.aimon.core.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.exception.FileAccessDeniedException;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

@DisplayName("VirtualFileSystems")
class VirtualFileSystemsTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem raw;

    @BeforeEach
    void setUp() {
        raw = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        raw.initialize();
        raw.write(".aimon/state.json", "secret");
        raw.write(".aimon-staged/s/SKILL.md", "staged");
        raw.write("src/a.txt", "work");
    }

    @AfterEach
    void tearDown() {
        raw.close();
    }

    @Test
    @DisplayName("withPathRules hides DENY prefixes and refuses writes under READ_ONLY prefixes")
    void withPathRulesAppliesRules() {
        final VirtualFileSystem fs = VirtualFileSystems.withPathRules(raw,
                List.of(PathRule.deny(".aimon"), PathRule.readOnly(".aimon-staged")));

        assertThat(fs.exists(".aimon/state.json")).isFalse();
        assertThat(readString(fs, ".aimon-staged/s/SKILL.md")).isEqualTo("staged");
        assertThatThrownBy(() -> fs.write(".aimon-staged/s/SKILL.md", "edited"))
                .isInstanceOf(FileAccessDeniedException.class);
        fs.write("src/a.txt", "edited");
        assertThat(readString(raw, "src/a.txt")).isEqualTo("edited");
    }

    @Test
    @DisplayName("withPathRules borrows the delegate: closing the result leaves the delegate usable")
    void withPathRulesBorrowsDelegate() {
        final VirtualFileSystem fs = VirtualFileSystems.withPathRules(raw, List.of(PathRule.deny(".aimon")));

        fs.close();

        assertThat(readString(raw, "src/a.txt")).isEqualTo("work");
    }

    @Test
    @DisplayName("withPathRules with no rules passes everything through")
    void withPathRulesEmpty() {
        final VirtualFileSystem fs = VirtualFileSystems.withPathRules(raw, List.of());

        assertThat(readString(fs, ".aimon/state.json")).isEqualTo("secret");
    }

    private static String readString(VirtualFileSystem fs, String path) {
        try (InputStream in = fs.read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
