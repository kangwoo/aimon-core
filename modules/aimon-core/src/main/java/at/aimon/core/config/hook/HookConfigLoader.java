package at.aimon.core.config.hook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
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
 * resulting {@link LayeredHookConfig}. A path one of whose components is a regular file (e.g. {@code ~/.aimon} is a
 * file) cannot hold the file either: skipped too, with a WARN naming that component.
 * <li><b>Empty files</b> &mdash; a file that is empty, whitespace only or {@code null} is an empty document: the layer
 * is present and declares no hooks.
 * <li><b>Parse failures</b> (malformed JSON, unknown handler {@code type}, a non-positive timeout) &mdash; raise
 * {@link HookConfigParseException}, whose message names the file, its layer and the parser's detail (line and
 * column for malformed JSON).
 * <li><b>Files that are there but cannot be read</b> &mdash; a non-regular file (e.g. a directory at the expected
 * path), an I/O error (permission denied, transient FS errors), or a path whose existence cannot be determined
 * (an unsearchable parent directory; the message carries the underlying exception class and message) &mdash; raise
 * {@link HookConfigParseException} as well. Treating such a layer
 * as absent would take every guard it declares off without a word, which is the failure a parse error is.
 * <li><b>What the caller does with the exception</b> &mdash; at startup it stops the host
 * ({@link HookRegistryReloader#bootstrap()} propagates it); on a hot reload the previous config stays in force
 * ({@link HookRegistryReloader#reload}).
 * <li><b>A {@code failOpen} that is not a JSON boolean</b> &mdash; not a parse failure. The handler is kept with
 * {@code failOpen} read as {@code false}, so its guard stays closed, and a WARN names the file, the event and the
 * handler. Failing the parse would drop every handler in the file, i.e. take all its guards off (EE-51).
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
     * Loads the three layered files and returns the result. Only a missing file contributes an absent entry (see
     * class-level Javadoc for the full policy).
     *
     * @return layered config (never null)
     * @throws HookConfigParseException
     *             when any file that is present fails to parse or cannot be read; the message names the file and its
     *             layer
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
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            log.debug("hooks config not present for {} at {}", source, path);
            return Optional.empty();
        } catch (IOException e) {
            // A path component that is a regular file (~/.aimon is a file) means the config directory, and so the
            // file, cannot exist: a definite absence, not a guard that cannot judge. Anything else (an unsearchable
            // directory, an I/O error) leaves existence unknown, and an unknown layer must not read as "no hooks".
            final Optional<Path> fileAncestor = regularFileAncestor(path);
            if (fileAncestor.isPresent()) {
                log.warn("hooks config not present for {} at {}: {} is a file, not a directory", source,
                        path.toAbsolutePath(), fileAncestor.get().toAbsolutePath());
                return Optional.empty();
            }
            throw new HookConfigParseException(
                    fileLabel(path, source) + " could not be read: cannot determine whether the file exists ("
                            + e.getClass().getName() + ": " + e.getMessage() + ")",
                    e);
        }
        if (!attributes.isRegularFile()) {
            throw new HookConfigParseException(fileLabel(path, source) + " could not be read: not a regular file");
        }
        try {
            final HookConfigDocument doc = parser.parseFile(path);
            log.debug("loaded hooks config from {}: {}", path, doc);
            warnRejectedFailOpen(path, doc);
            return Optional.of(doc);
        } catch (HookConfigParseException e) {
            throw new HookConfigParseException(fileLabel(path, source) + " is invalid: " + e.getMessage(), e);
        } catch (UncheckedIOException e) {
            throw new HookConfigParseException(fileLabel(path, source) + " could not be read: " + e.getCause(), e);
        }
    }

    /** The nearest proper ancestor of {@code path} that is a regular file, if any. */
    private static Optional<Path> regularFileAncestor(Path path) {
        for (Path p = path.toAbsolutePath().getParent(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private static String fileLabel(Path path, HookConfigSource source) {
        return "hooks config " + path.toAbsolutePath() + " (" + source + " layer)";
    }

    private static void warnRejectedFailOpen(Path path, HookConfigDocument doc) {
        for (Map.Entry<String, List<HookEntry>> event : doc.getHooks().entrySet()) {
            for (HookEntry entry : event.getValue()) {
                for (HookHandlerSpec handler : entry.getHandlers()) {
                    handler.getRejectedFailOpen()
                            .ifPresent(raw -> log.warn("hooks config at {}: {} handler {} on {} has a 'failOpen' that"
                                    + " is not a JSON boolean ({}); it is read as false, so the handler blocks when"
                                    + " its command cannot run", path, handler.getType(), describe(handler),
                                    event.getKey(), raw));
                }
            }
        }
    }

    private static String describe(HookHandlerSpec handler) {
        return switch (handler.getType()) {
            case COMMAND -> "'" + handler.getCommand() + "'";
            case HTTP -> "'" + handler.getUrl() + "'";
            case MCP -> "'" + handler.getServerName() + "/" + handler.getToolName() + "'";
            case DENY -> "(deny)";
        };
    }
}
