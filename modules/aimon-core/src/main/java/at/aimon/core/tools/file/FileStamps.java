package at.aimon.core.tools.file;

import java.util.Map;
import java.util.Optional;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.FileStamp;
import at.aimon.core.filesystem.VfsPaths;

/**
 * The read-stamp bookkeeping {@code Read}, {@code Edit} and {@code Write} share (execution-environment design §7).
 *
 * <p>
 * Stamps are keyed by the environment-normalised path, not the string the model passed, so {@code a.txt},
 * {@code ./a.txt} and an absolute path under the working directory are one key. The base is the environment
 * descriptor's working directory when that is a host path ({@code '/'}-anchored — local and isolated environments,
 * where it is the directory the shell and absolute file-tool paths agree on), and the filesystem's own working
 * directory otherwise (URI-shaped remote bases). A context without {@link ReadTool#FILE_STAMPS_KEY} — a hand-built
 * context, a custom invoker — opts out of the check, as it always has.
 */
final class FileStamps {

    /** The error when a file that exists was not read in this execution. */
    static final String NOT_READ_MESSAGE = "Read the file before modifying it";

    /** The error when a file changed after it was read. */
    static final String CHANGED_MESSAGE = "File changed since it was read; Read it again";

    private FileStamps() {
    }

    /**
     * Returns the stamp-map key for a path.
     *
     * @param env
     *            the execution environment
     * @param path
     *            the path the model passed
     * @return the normalised key
     */
    static String key(ExecutionEnvironment env, String path) {
        final String descriptorDirectory = env.descriptor().workingDirectory();
        final String base = descriptorDirectory.startsWith("/")
                ? descriptorDirectory
                : env.fileSystem().getWorkingDirectory();
        final String key = VfsPaths.rootRelative(base, path);
        return key == null ? path : key;
    }

    /**
     * Stamps the file as it is now.
     *
     * @param env
     *            the execution environment
     * @param path
     *            the file path
     * @return the current stamp
     */
    static FileStamp current(ExecutionEnvironment env, String path) {
        return FileStamp.of(env.fileSystem().getMetadata(path));
    }

    /**
     * Records a stamp for a path, when the context tracks stamps.
     *
     * @param context
     *            the tool context
     * @param env
     *            the execution environment
     * @param path
     *            the file path
     * @param stamp
     *            the stamp to record
     */
    static void record(ToolContext context, ExecutionEnvironment env, String path, FileStamp stamp) {
        context.get(ReadTool.FILE_STAMPS_KEY).ifPresent(stamps -> stamps.put(key(env, path), stamp));
    }

    /**
     * Refreshes the recorded stamp after a successful write, when the context tracks stamps.
     *
     * @param context
     *            the tool context
     * @param env
     *            the execution environment
     * @param path
     *            the file path
     */
    static void refresh(ToolContext context, ExecutionEnvironment env, String path) {
        final Optional<Map<String, FileStamp>> stamps = context.get(ReadTool.FILE_STAMPS_KEY);
        if (stamps.isPresent()) {
            stamps.get().put(key(env, path), current(env, path));
        }
    }

    /**
     * Checks that an existing file was read in this execution and has not changed since. A file that does not exist
     * needs no check (a new file), nor does a context that tracks no stamps.
     *
     * @param context
     *            the tool context
     * @param env
     *            the execution environment
     * @param path
     *            the file path
     * @return the error message to return, or empty if the modification may proceed
     */
    static Optional<String> checkBeforeModify(ToolContext context, ExecutionEnvironment env, String path) {
        return checkBeforeModify(context, env, path, false);
    }

    /**
     * As {@link #checkBeforeModify(ToolContext, ExecutionEnvironment, String)}, but with {@code requireTracking} a
     * context that tracks no stamps counts as "not read" — {@code Edit}'s rule, which has always refused to edit
     * without a record of the read.
     */
    static Optional<String> checkBeforeModify(ToolContext context, ExecutionEnvironment env, String path,
            boolean requireTracking) {
        final Optional<Map<String, FileStamp>> stamps = context.get(ReadTool.FILE_STAMPS_KEY);
        if (stamps.isEmpty()) {
            return requireTracking ? Optional.of(NOT_READ_MESSAGE) : Optional.empty();
        }
        if (!env.fileSystem().exists(path)) {
            return Optional.empty();
        }
        final FileStamp recorded = stamps.get().get(key(env, path));
        if (recorded == null) {
            return Optional.of(NOT_READ_MESSAGE);
        }
        if (!recorded.equals(current(env, path))) {
            return Optional.of(CHANGED_MESSAGE);
        }
        return Optional.empty();
    }
}
