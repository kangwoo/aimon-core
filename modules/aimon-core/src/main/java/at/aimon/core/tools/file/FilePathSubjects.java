package at.aimon.core.tools.file;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.permission.PermissionSubject;
import at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Builds the {@link PermissionSubject.Kind#PATH} subject the file tools are judged on.
 *
 * <p>
 * {@code Read}, {@code Edit} and {@code Write} all take the same {@code file_path} argument and all want the same
 * answer, so the derivation lives here rather than three times over. It stays package-private and on this side of the
 * boundary deliberately: the permission package must not know which parameter name a tool happens to use, and it must
 * not depend on {@code at.aimon.core.tools} to find out.
 *
 * <h2>Absolute and normalized, or nothing</h2>
 *
 * <p>
 * {@link PermissionSubject.Kind#PATH} requires an absolute, lexically normalized path — a pattern author writes
 * {@code Read(/tmp/**)} and should not also have to anticipate {@code /tmp/../tmp/x} or {@code /tmp/./x}. Normalizing
 * first is also what makes {@code /allowed/../secrets} a denial rather than a match on the {@code /allowed} prefix.
 *
 * <p>
 * A relative path is resolved against the {@link Environment}'s working directory, and only that: with no environment
 * in the context there is no answer, and the subject is empty. Falling back to the process CWD would make the verdict
 * depend on where the JVM was started, which the person writing the pattern cannot see.
 *
 * <h2>Lexical only</h2>
 *
 * <p>
 * Nothing here touches the filesystem — no {@code toRealPath()}, no symlink resolution. The permission check runs
 * before the tool does and has no handle to resolve links with, so {@code /allowed/link → /secrets} passes a
 * {@code /allowed/**} pattern. Permission patterns narrow what the agent may ask for; containment is the sandbox's job.
 *
 * @see ToolPermissionSubjectAware
 */
final class FilePathSubjects {

    /** The argument every file tool names its target with. */
    private static final String FILE_PATH_PARAM = "file_path";

    private FilePathSubjects() {
        // Utility class
    }

    /**
     * Derives the path subject for a file tool call.
     *
     * @param input
     *            The tool call's arguments (must not be null)
     * @param context
     *            The runtime context, consulted for the working directory (must not be null)
     * @return The subject, or empty when the call cannot be judged — no {@code file_path}, a string this platform
     *         cannot read as a path, or a relative path with no {@link Environment} to resolve it against
     */
    static Optional<PermissionSubject> filePathSubject(ToolInput input, ToolContext context) {
        // Read the raw value rather than ToolInput#getStringOrNull: that accessor throws when the key holds a
        // non-string, and this runs ahead of the tool, where there is nothing to catch it. A file_path that is not a
        // string cannot be judged, which is the same answer as one that is missing.
        if (!(input.get(FILE_PATH_PARAM) instanceof String rawPath) || rawPath.isBlank()) {
            return Optional.empty();
        }
        return absoluteNormalized(rawPath, context).map(PermissionSubject::path);
    }

    private static Optional<String> absoluteNormalized(String rawPath, ToolContext context) {
        final Path path = toPath(rawPath);
        if (path == null) {
            return Optional.empty();
        }
        if (path.isAbsolute()) {
            return Optional.of(path.normalize().toString());
        }
        return context.get(ToolContextKeys.ENVIRONMENT_KEY).map(Environment::getWorkingDirectory)
                .flatMap(workingDirectory -> resolveAgainst(workingDirectory, path));
    }

    private static Optional<String> resolveAgainst(String workingDirectory, Path relativePath) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return Optional.empty();
        }
        final Path base = toPath(workingDirectory);
        if (base == null || !base.isAbsolute()) {
            // A relative working directory would only push the guess one level out — still unpredictable.
            return Optional.empty();
        }
        return Optional.of(base.resolve(relativePath).normalize().toString());
    }

    private static Path toPath(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
