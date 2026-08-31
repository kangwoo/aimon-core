package at.aimon.core.config.hook;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads Claude Code-compatible {@code hooks.json} files from the four well-known locations and produces a
 * {@link LayeredHookConfig}.
 *
 * <p>
 * Sources looked up by default:
 * <ul>
 * <li>{@link HookConfigSource#USER} &rarr; {@code ~/.aimon/hooks.json}
 * <li>{@link HookConfigSource#PROJECT} &rarr; {@code <project>/.aimon/hooks.json}
 * <li>{@link HookConfigSource#LOCAL} &rarr; {@code <project>/.aimon/hooks.local.json}
 * </ul>
 *
 * <p>
 * SKILL entries are not loaded here &mdash; they originate in the skill frontmatter and are added to the layered
 * config by the skill-loading pipeline. See {@code SkillFrontmatterHookParser}.
 *
 * <p>
 * Loading policy:
 * <ul>
 * <li><b>Missing files</b> &mdash; silently skipped (DEBUG log). The corresponding source is left absent in the
 * resulting {@link LayeredHookConfig}.
 * <li><b>Non-regular files</b> (e.g. a directory at the expected path) &mdash; skipped with a WARN log.
 * <li><b>I/O errors</b> (permission denied, transient FS errors) &mdash; skipped with a WARN log; the source is
 * treated as absent. This is intentional fail-soft behaviour so a misconfigured filesystem does not block agent
 * startup; operators must scan WARN logs to detect such cases.
 * <li><b>Parse failures</b> (malformed JSON, unknown handler {@code type}) &mdash; raise
 * {@link HookConfigParseException}. The caller is expected to surface this to the user at startup so the bad
 * file can be fixed.
 * </ul>
 *
 * <p>
 * Thread-safe; the loader holds no per-call state beyond the supplied parser.
 */
public final class HookConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(HookConfigLoader.class);

    /** Standard filename for the user / project layer ({@code hooks.json}). */
    public static final String DEFAULT_FILE_NAME = "hooks.json";

    /** Standard filename for the local override layer ({@code hooks.local.json}). */
    public static final String LOCAL_FILE_NAME = "hooks.local.json";

    /** Standard {@code .aimon} sub-directory under the user home / project root. */
    public static final String DEFAULT_DIRECTORY = ".aimon";

    private final JacksonHookConfigParser parser;
    private final Path userConfigDir;
    private final Path projectConfigDir;

    /**
     * Convenience factory pointing at the OS-native locations: {@code ~/.aimon} and {@code $PWD/.aimon}, derived from
     * the {@code user.home} and {@code user.dir} system properties respectively (both falling back to {@code "."}).
     *
     * <p>
     * <b>Embedded / containerized hosts should not use this factory.</b> Inside a container {@code user.home} is
     * typically {@code /} or {@code /root} and {@code user.dir} is the JVM's launch directory, neither of which is
     * the host's config location. Such hosts should call {@link #createDefault(Path, Path)} with explicitly
     * configured roots, or the {@linkplain #HookConfigLoader(JacksonHookConfigParser, Path, Path) constructor} when
     * they want to bypass the {@code .aimon} layout entirely. The derived values are kept as a documented fallback
     * for CLI / desktop use only.
     *
     * @return loader using the default paths
     */
    public static HookConfigLoader createDefault() {
        return createDefault(Path.of(System.getProperty("user.home", ".")),
                Path.of(System.getProperty("user.dir", ".")));
    }

    /**
     * Convenience factory taking the two roots explicitly and appending the standard {@link #DEFAULT_DIRECTORY}
     * sub-directory to each, i.e. {@code <userHome>/.aimon} and {@code <projectRoot>/.aimon}.
     *
     * <p>
     * This is the factory embedded and containerized hosts should use: it accepts the roots the host actually knows
     * (its configuration directory and its workspace root) instead of deriving them from the {@code user.home} /
     * {@code user.dir} system properties, which are unreliable outside a desktop shell. Callers that already hold the
     * fully resolved config directories &mdash; or that use a layout other than {@code <root>/.aimon} &mdash; should
     * use the {@linkplain #HookConfigLoader(JacksonHookConfigParser, Path, Path) constructor} instead.
     *
     * @param userHome
     *            root containing the user-level {@code .aimon} directory (must not be null; it need not exist yet
     *            &mdash; missing files are skipped)
     * @param projectRoot
     *            root containing the project-level {@code .aimon} directory (must not be null)
     * @return loader rooted at the supplied paths
     */
    public static HookConfigLoader createDefault(Path userHome, Path projectRoot) {
        Objects.requireNonNull(userHome, "userHome cannot be null");
        Objects.requireNonNull(projectRoot, "projectRoot cannot be null");
        return new HookConfigLoader(new JacksonHookConfigParser(), userHome.resolve(DEFAULT_DIRECTORY),
                projectRoot.resolve(DEFAULT_DIRECTORY));
    }

    /**
     * Creates a loader.
     *
     * @param parser
     *            JSON parser (must not be null)
     * @param userConfigDir
     *            directory containing the user-level {@code hooks.json} (must not be null; directory may not exist
     *            yet &mdash; missing files are skipped)
     * @param projectConfigDir
     *            directory containing the project {@code hooks.json} and {@code hooks.local.json} (must not be null)
     */
    public HookConfigLoader(JacksonHookConfigParser parser, Path userConfigDir, Path projectConfigDir) {
        this.parser = Objects.requireNonNull(parser, "parser cannot be null");
        this.userConfigDir = Objects.requireNonNull(userConfigDir, "userConfigDir cannot be null");
        this.projectConfigDir = Objects.requireNonNull(projectConfigDir, "projectConfigDir cannot be null");
    }

    /**
     * Loads the three layered files and returns the result. Missing files, non-regular files, and unreadable files
     * contribute an absent entry (see class-level Javadoc for the full policy).
     *
     * @return layered config (never null)
     * @throws HookConfigParseException
     *             when any present and readable file fails to parse
     */
    public LayeredHookConfig load() {
        final LayeredHookConfig.Builder b = LayeredHookConfig.builder();
        loadOptional(userConfigDir.resolve(DEFAULT_FILE_NAME), HookConfigSource.USER)
                .ifPresent(doc -> b.put(HookConfigSource.USER, doc));
        loadOptional(projectConfigDir.resolve(DEFAULT_FILE_NAME), HookConfigSource.PROJECT)
                .ifPresent(doc -> b.put(HookConfigSource.PROJECT, doc));
        loadOptional(projectConfigDir.resolve(LOCAL_FILE_NAME), HookConfigSource.LOCAL)
                .ifPresent(doc -> b.put(HookConfigSource.LOCAL, doc));
        return b.build();
    }

    private Optional<HookConfigDocument> loadOptional(Path path, HookConfigSource source) {
        if (!Files.exists(path)) {
            log.debug("hooks config not present for {} at {}", source, path);
            return Optional.empty();
        }
        if (!Files.isRegularFile(path)) {
            log.warn("hooks config path for {} exists but is not a regular file: {}", source, path);
            return Optional.empty();
        }
        try {
            final HookConfigDocument doc = parser.parseFile(path);
            log.debug("loaded hooks config from {}: {}", path, doc);
            return Optional.of(doc);
        } catch (UncheckedIOException e) {
            log.warn("hooks config at {} could not be read: {}", path, e.getMessage());
            return Optional.empty();
        }
    }
}
