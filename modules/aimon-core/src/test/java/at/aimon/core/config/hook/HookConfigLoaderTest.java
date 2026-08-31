package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("HookConfigLoader")
class HookConfigLoaderTest {

    @Test
    @DisplayName("missing files yield an empty layered config")
    void missingFilesAreSkipped(@TempDir Path tmp) {
        final HookConfigLoader loader = new HookConfigLoader(new JacksonHookConfigParser(), tmp.resolve("user"),
                tmp.resolve("project"));

        final LayeredHookConfig config = loader.load();

        assertThat(config.layered()).isEmpty();
    }

    @Test
    @DisplayName("present files are loaded into the matching source slot")
    void loadsThreeLayers(@TempDir Path tmp) throws IOException {
        final Path userDir = Files.createDirectories(tmp.resolve("user"));
        final Path projDir = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(userDir.resolve("hooks.json"),
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"u\",\"hooks\":[{\"type\":\"command\",\"command\":\"cu\"}]}]}}");
        Files.writeString(projDir.resolve("hooks.json"),
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"p\",\"hooks\":[{\"type\":\"command\",\"command\":\"cp\"}]}]}}");
        Files.writeString(projDir.resolve("hooks.local.json"),
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"l\",\"hooks\":[{\"type\":\"command\",\"command\":\"cl\"}]}]}}");

        final HookConfigLoader loader = new HookConfigLoader(new JacksonHookConfigParser(), userDir, projDir);
        final LayeredHookConfig config = loader.load();

        assertThat(config.get(HookConfigSource.USER).getHooks()).containsKey("PreToolUse");
        assertThat(config.get(HookConfigSource.PROJECT).getHooks()).containsKey("PreToolUse");
        assertThat(config.get(HookConfigSource.LOCAL).getHooks()).containsKey("PreToolUse");
        assertThat(config.layeredAscending()).extracting(java.util.Map.Entry::getKey)
                .containsExactly(HookConfigSource.USER, HookConfigSource.PROJECT, HookConfigSource.LOCAL);
    }

    @Test
    @DisplayName("createDefault(userHome, projectRoot) resolves .aimon under each explicit root")
    void createDefaultWithExplicitRootsResolvesAimonSubdirectories(@TempDir Path tmp) throws IOException {
        final Path userHome = tmp.resolve("home");
        final Path projectRoot = tmp.resolve("project");
        writeHooks(userHome.resolve(".aimon").resolve("hooks.json"), "u");
        writeHooks(projectRoot.resolve(".aimon").resolve("hooks.json"), "p");
        writeHooks(projectRoot.resolve(".aimon").resolve("hooks.local.json"), "l");

        final LayeredHookConfig config = HookConfigLoader.createDefault(userHome, projectRoot).load();

        assertThat(config.get(HookConfigSource.USER).getHooks()).containsKey("PreToolUse");
        assertThat(config.get(HookConfigSource.PROJECT).getHooks()).containsKey("PreToolUse");
        assertThat(config.get(HookConfigSource.LOCAL).getHooks()).containsKey("PreToolUse");
        assertThat(config.layeredAscending()).extracting(java.util.Map.Entry::getKey)
                .containsExactly(HookConfigSource.USER, HookConfigSource.PROJECT, HookConfigSource.LOCAL);
        assertThat(matcherOf(config, HookConfigSource.USER)).isEqualTo("u");
        assertThat(matcherOf(config, HookConfigSource.PROJECT)).isEqualTo("p");
        assertThat(matcherOf(config, HookConfigSource.LOCAL)).isEqualTo("l");
    }

    @Test
    @DisplayName("no-arg createDefault() still derives the roots from user.home / user.dir")
    void noArgCreateDefaultStillReadsUserHomeAndUserDir(@TempDir Path tmp) throws IOException {
        final Path userHome = tmp.resolve("home");
        final Path projectRoot = tmp.resolve("project");
        writeHooks(userHome.resolve(".aimon").resolve("hooks.json"), "from-user-home");
        writeHooks(projectRoot.resolve(".aimon").resolve("hooks.json"), "from-user-dir");
        writeHooks(projectRoot.resolve(".aimon").resolve("hooks.local.json"), "from-user-dir-local");

        final String originalUserHome = System.getProperty("user.home");
        final String originalUserDir = System.getProperty("user.dir");
        final LayeredHookConfig config;
        try {
            System.setProperty("user.home", userHome.toAbsolutePath().toString());
            System.setProperty("user.dir", projectRoot.toAbsolutePath().toString());
            config = HookConfigLoader.createDefault().load();
        } finally {
            System.setProperty("user.home", originalUserHome);
            System.setProperty("user.dir", originalUserDir);
        }

        assertThat(matcherOf(config, HookConfigSource.USER)).isEqualTo("from-user-home");
        assertThat(matcherOf(config, HookConfigSource.PROJECT)).isEqualTo("from-user-dir");
        assertThat(matcherOf(config, HookConfigSource.LOCAL)).isEqualTo("from-user-dir-local");
    }

    @Test
    @DisplayName("createDefault(userHome, projectRoot) rejects null roots")
    void createDefaultWithNullRootsThrows(@TempDir Path tmp) {
        assertThatThrownBy(() -> HookConfigLoader.createDefault(null, tmp)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userHome cannot be null");
        assertThatThrownBy(() -> HookConfigLoader.createDefault(tmp, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("projectRoot cannot be null");
    }

    private static void writeHooks(Path file, String matcher) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"" + matcher
                + "\",\"hooks\":[{\"type\":\"command\",\"command\":\"c\"}]}]}}");
    }

    private static String matcherOf(LayeredHookConfig config, HookConfigSource source) {
        return config.get(source).getHooks().get("PreToolUse").get(0).getMatcher();
    }
}
