package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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
    @DisplayName("a non-boolean failOpen keeps the handler closed and WARNs with the file and the handler (EE-51)")
    void nonBooleanFailOpenIsReadAsFalseWithAWarning(@TempDir Path tmp) throws IOException {
        final Path projDir = Files.createDirectories(tmp.resolve("project"));
        final Path file = projDir.resolve("hooks.json");
        Files.writeString(file,
                "{\"hooks\":{\"PreToolUse\":[{\"hooks\":["
                        + "{\"type\":\"command\",\"command\":\"guard.sh\",\"failOpen\":\"yes\"},"
                        + "{\"type\":\"command\",\"command\":\"other.sh\"}]}]}}");
        final ch.qos.logback.classic.Logger loaderLogger = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(HookConfigLoader.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        loaderLogger.addAppender(appender);
        try {
            final LayeredHookConfig config = new HookConfigLoader(new JacksonHookConfigParser(), tmp.resolve("user"),
                    projDir).load();

            // Both handlers survive — the file is not refused, so no guard is lost — and the bad one is closed.
            assertThat(config.get(HookConfigSource.PROJECT).getHooks().get("PreToolUse").get(0).getHandlers())
                    .extracting(HookHandlerSpec::getCommand, HookHandlerSpec::isFailOpen)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("guard.sh", false),
                            org.assertj.core.groups.Tuple.tuple("other.sh", false));
            final List<ILoggingEvent> warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                    .toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getFormattedMessage()).contains(file.toString()).contains("guard.sh")
                    .contains("PreToolUse").contains("\"yes\"").contains("read as false");
        } finally {
            loaderLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("a non-boolean ignoreInterrupt is read as false and WARNs with the file and the handler (EE-97)")
    void nonBooleanIgnoreInterruptIsReadAsFalseWithAWarning(@TempDir Path tmp) throws IOException {
        final Path projDir = Files.createDirectories(tmp.resolve("project"));
        final Path file = projDir.resolve("hooks.json");
        Files.writeString(file, "{\"hooks\":{\"Stop\":[{\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"cleanup.sh\",\"ignoreInterrupt\":\"yes\"}]}]}}");
        final ch.qos.logback.classic.Logger loaderLogger = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(HookConfigLoader.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        loaderLogger.addAppender(appender);
        try {
            final LayeredHookConfig config = new HookConfigLoader(new JacksonHookConfigParser(), tmp.resolve("user"),
                    projDir).load();

            assertThat(config.get(HookConfigSource.PROJECT).getHooks().get("Stop").get(0).getHandlers())
                    .extracting(HookHandlerSpec::getCommand, HookHandlerSpec::isIgnoreInterrupt)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("cleanup.sh", false));
            final List<ILoggingEvent> warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                    .toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getFormattedMessage()).contains(file.toString()).contains("cleanup.sh")
                    .contains("'ignoreInterrupt'").contains("\"yes\"").contains("read as false");
        } finally {
            loaderLogger.detachAppender(appender);
            appender.stop();
        }
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

    // --- EE-71 ----------------------------------------------------------------------------------------------------

    private static final String BROKEN_JSON = "{\"hooks\":{\"PreToolUse\":[\n  {not valid json";
    private static final String UNKNOWN_TYPE = "{\"hooks\":{\"PreToolUse\":[{\"hooks\":[{\"type\":\"comand\",\"command\":\"c\"}]}]}}";
    private static final String NEGATIVE_TIMEOUT = "{\"hooks\":{\"PreToolUse\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"c\",\"timeout\":-5}]}]}}";

    static Stream<Arguments> brokenFiles() {
        return Stream.of(Arguments.of(HookConfigSource.USER, BROKEN_JSON, "line: 2"),
                Arguments.of(HookConfigSource.PROJECT, UNKNOWN_TYPE, "Unknown hook handler type"),
                Arguments.of(HookConfigSource.LOCAL, NEGATIVE_TIMEOUT, "must be a positive number"),
                Arguments.of(HookConfigSource.PROJECT, BROKEN_JSON, "line: 2"),
                Arguments.of(HookConfigSource.USER, NEGATIVE_TIMEOUT, "must be a positive number"),
                Arguments.of(HookConfigSource.LOCAL, UNKNOWN_TYPE, "Unknown hook handler type"));
    }

    private static Path fileOf(Path tmp, HookConfigSource source) {
        return switch (source) {
            case USER -> tmp.resolve("user").resolve("hooks.json");
            case PROJECT -> tmp.resolve("project").resolve("hooks.json");
            case LOCAL -> tmp.resolve("project").resolve("hooks.local.json");
            default -> throw new IllegalArgumentException(source.name());
        };
    }

    private static HookConfigLoader loaderOver(Path tmp) {
        return new HookConfigLoader(new JacksonHookConfigParser(), tmp.resolve("user"), tmp.resolve("project"));
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("brokenFiles")
    @DisplayName("a file that does not parse fails the load, naming the file, its layer and the cause (EE-71)")
    void parseFailureNamesTheFileTheLayerAndTheCause(HookConfigSource source, String content, String cause,
            @TempDir Path tmp) throws IOException {
        final Path file = fileOf(tmp, source);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);

        assertThatThrownBy(() -> loaderOver(tmp).load()).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("hooks config " + file.toAbsolutePath())
                .hasMessageContaining("(" + source + " layer) is invalid").hasMessageContaining(cause)
                .hasCauseInstanceOf(HookConfigParseException.class);
    }

    @Test
    @DisplayName("a directory where the file should be fails the load instead of reading as absent (EE-71)")
    void nonRegularFileFailsTheLoad(@TempDir Path tmp) throws IOException {
        final Path file = fileOf(tmp, HookConfigSource.PROJECT);
        Files.createDirectories(file);

        assertThatThrownBy(() -> loaderOver(tmp).load()).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining(file.toAbsolutePath().toString())
                .hasMessageContaining("(PROJECT layer) could not be read").hasMessageContaining("not a regular file");
    }

    @Test
    @DisplayName("a file that cannot be read fails the load instead of reading as absent (EE-71)")
    void unreadableFileFailsTheLoad(@TempDir Path tmp) throws IOException {
        final Path file = fileOf(tmp, HookConfigSource.USER);
        writeHooks(file, "u");
        assumeTrue(file.toFile().setReadable(false) && !Files.isReadable(file), "cannot revoke read permission here");
        try {
            assertThatThrownBy(() -> loaderOver(tmp).load()).isInstanceOf(HookConfigParseException.class)
                    .hasMessageContaining(file.toAbsolutePath().toString())
                    .hasMessageContaining("(USER layer) could not be read");
        } finally {
            file.toFile().setReadable(true);
        }
    }

    @Test
    @DisplayName("a config directory that cannot be searched fails the load: absent and unknowable differ (EE-71)")
    void unsearchableDirectoryFailsTheLoad(@TempDir Path tmp) throws IOException {
        final Path file = fileOf(tmp, HookConfigSource.PROJECT);
        writeHooks(file, "p");
        final java.io.File dir = file.getParent().toFile();
        assumeTrue(dir.setExecutable(false) && !Files.exists(file) && !Files.notExists(file),
                "cannot make the directory unsearchable here");
        try {
            assertThatThrownBy(() -> loaderOver(tmp).load()).isInstanceOf(HookConfigParseException.class)
                    .hasMessageContaining(file.toAbsolutePath().toString())
                    .hasMessageContaining("(PROJECT layer) could not be read")
                    .hasMessageContaining("cannot determine whether the file exists")
                    .hasMessageContaining("java.nio.file.AccessDeniedException")
                    .hasCauseInstanceOf(java.nio.file.AccessDeniedException.class);
        } finally {
            dir.setExecutable(true);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyContents")
    @DisplayName("an empty, whitespace-only or null file is a present layer with no hooks, not a startup failure")
    void emptyFileIsALayerWithNoHooks(String label, String content, @TempDir Path tmp) throws IOException {
        final Path file = fileOf(tmp, HookConfigSource.PROJECT);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        writeHooks(fileOf(tmp, HookConfigSource.USER), "u");

        final LayeredHookConfig config = loaderOver(tmp).load();

        assertThat(config.get(HookConfigSource.PROJECT).getHooks()).isEmpty();
        assertThat(config.get(HookConfigSource.USER).getHooks()).containsKey("PreToolUse");
    }

    static Stream<Arguments> emptyContents() {
        return Stream.of(Arguments.of("zero bytes", ""), Arguments.of("whitespace only", " \n\t\r\n "),
                Arguments.of("null", "null\n"));
    }

    @Test
    @DisplayName("a config directory that is a regular file reads as absent with a WARN naming it")
    void configDirectoryThatIsAFileReadsAsAbsent(@TempDir Path tmp) throws IOException {
        final Path userDir = tmp.resolve("user");
        Files.writeString(userDir, "not a directory");
        writeHooks(fileOf(tmp, HookConfigSource.PROJECT), "p");
        final ch.qos.logback.classic.Logger loaderLogger = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(HookConfigLoader.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        loaderLogger.addAppender(appender);
        try {
            final LayeredHookConfig config = loaderOver(tmp).load();

            assertThat(config.layered()).containsOnlyKeys(HookConfigSource.PROJECT);
            assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage).singleElement()
                    .satisfies(message -> assertThat(message).contains(userDir.toAbsolutePath().toString(),
                            "is a file, not a directory"));
        } finally {
            loaderLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("a file that is not valid UTF-8 fails the load as invalid")
    void invalidUtf8FailsTheLoad(@TempDir Path tmp) throws IOException {
        final Path file = fileOf(tmp, HookConfigSource.LOCAL);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{'{', (byte) 0xC3, (byte) 0x28, '}'});

        assertThatThrownBy(() -> loaderOver(tmp).load()).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("(LOCAL layer) is invalid").hasMessageContaining("not valid UTF-8");
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
