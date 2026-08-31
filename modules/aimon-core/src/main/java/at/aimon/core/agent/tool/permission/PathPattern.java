package at.aimon.core.agent.tool.permission;

import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.exception.InvalidToolSpecException;

/**
 * Glob matcher for {@link PermissionSubject.Kind#PATH} subjects — the path half of the permission grammar.
 *
 * <p>
 * The pattern is a glob as {@link FileSystems#getDefault()} defines it, matched against the <b>whole</b> path:
 *
 * <ul>
 * <li>{@code /tmp/**} — anything under {@code /tmp}, at any depth
 * <li>{@code /tmp/*.log} — log files directly in {@code /tmp}, and not in its subdirectories ({@code *} does not cross
 * a separator)
 * <li>{@code /etc/passwd} — that one file, exactly
 * </ul>
 *
 * <h2>Why {@link ToolPattern} could not be reused</h2>
 *
 * <p>
 * {@code ToolPattern} is a command matcher. It recognises a wildcard only by a trailing {@code :*}, and it rejects any
 * candidate containing {@code ; | &amp; ` $ &gt; &lt; ( )} — a defence against shell injection that is correct for a
 * string on its way to {@code bash -c}. A path never reaches a shell, so that threat model does not apply, and keeping
 * the rejection would put a normal file named {@code report(1).csv} permanently out of reach. Hence a second matcher,
 * selected by {@link PermissionSubject.Kind} rather than by inspecting the spec string.
 *
 * <h2>Why nothing in the tree could be copied</h2>
 *
 * <p>
 * Four places already build a {@code getPathMatcher("glob:…")}, and none of them does what is needed here.
 * {@code LocalFileSystem}, {@code GridFSFileSystem} and {@code S3FileSystem} all push the pattern through
 * {@code SearchPatterns.sanitize} first, which strips {@code /} and folds {@code **} — {@code /tmp/**} arrives as
 * {@code tmp*}, with the directory boundary gone. The fourth, {@code GrepTool}, passes the pattern through intact but
 * matches it against {@code Paths.get(file).getFileName()} — the last segment only, which is a filename filter and
 * cannot express "under this directory". This class is therefore the first place a full path is matched against an
 * unaltered glob.
 *
 * <h2>Lexical only</h2>
 *
 * <p>
 * Matching is a string comparison over a normalized path. Symbolic links are not resolved — the permission path has no
 * filesystem handle to resolve them with — so {@code /allowed/link → /secrets} defeats {@code /allowed/**}. Permission
 * patterns narrow what an agent asks for; they are not an isolation boundary. Isolation is the sandbox's job.
 *
 * <p>
 * Immutable and thread-safe value object ({@link PathMatcher} instances from the default filesystem are safe to share).
 *
 * @see PermissionSubject
 * @see ToolPattern
 */
public final class PathPattern {

    private static final String GLOB_PREFIX = "glob:";

    private final String pattern;

    private final PathMatcher matcher;

    private PathPattern(String pattern, PathMatcher matcher) {
        this.pattern = pattern;
        this.matcher = matcher;
    }

    /**
     * Compiles a path pattern, rejecting a malformed glob.
     *
     * @param pattern
     *            The glob (must not be null)
     * @return A new PathPattern
     * @throws NullPointerException
     *             if pattern is null
     * @throws InvalidToolSpecException
     *             if the glob is malformed (an unbalanced brace group, say)
     */
    public static PathPattern of(String pattern) {
        return tryCompile(pattern)
                .orElseThrow(() -> new InvalidToolSpecException("Malformed path pattern: " + pattern));
    }

    /**
     * Compiles a path pattern, returning empty rather than throwing when the glob is malformed.
     *
     * <p>
     * This is the form the parser uses. A spec's kind is not knowable at parse time — {@code Bash(git:*)} and
     * {@code Read(/tmp/**)} are the same shape to {@link AllowedTool#parse} — so every pattern is speculatively
     * compiled as a glob, and a command pattern that happens not to be valid glob syntax must not fail the parse. The
     * absent matcher then denies at judgement time, which only matters if a tool actually presents a
     * {@link PermissionSubject.Kind#PATH} subject for that spec.
     *
     * @param pattern
     *            The glob (must not be null)
     * @return The compiled pattern, or empty if the glob is malformed
     * @throws NullPointerException
     *             if pattern is null
     */
    public static Optional<PathPattern> tryCompile(String pattern) {
        Objects.requireNonNull(pattern, "Pattern cannot be null");
        try {
            return Optional
                    .of(new PathPattern(pattern, FileSystems.getDefault().getPathMatcher(GLOB_PREFIX + pattern)));
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            // IllegalArgumentException covers PatternSyntaxException, which is what a malformed glob actually
            // arrives as; UnsupportedOperationException is the contract for a FileSystem without glob support.
            return Optional.empty();
        }
    }

    /**
     * Checks whether the given path matches this pattern.
     *
     * <p>
     * The caller is expected to pass an absolute, lexically normalized path — {@link PermissionSubject.Kind#PATH}
     * requires it. A path this platform cannot even represent (an embedded NUL, say) does not match.
     *
     * @param path
     *            The path to test (must not be null)
     * @return true if the path matches
     * @throws NullPointerException
     *             if path is null
     */
    public boolean matches(String path) {
        Objects.requireNonNull(path, "Path cannot be null");
        try {
            return matcher.matches(Path.of(path));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * Returns the original glob string.
     *
     * @return The pattern (never null)
     */
    public String getPattern() {
        return pattern;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PathPattern that = (PathPattern) o;
        return pattern.equals(that.pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pattern);
    }

    @Override
    public String toString() {
        return pattern;
    }
}
