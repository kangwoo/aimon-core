package at.aimon.core.shell;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Files a shell places in a fresh directory for one command, and the environment variable that names that directory
 * ({@link ExecutionOptions#getRunDirectory()}, {@link ShellFeature#RUN_DIRECTORY}).
 *
 * <p>
 * It carries bytes, not a path: the caller read and checked them, and the shell writes exactly these for the run and
 * removes them afterwards. Nothing persistent stands between what the caller checked and what the command opens, which
 * a staged copy in an agent-writable workspace cannot promise (EE-109). The paths are relative and confined below the
 * directory, and {@link #toString()} never shows the content.
 */
public final class RunDirectory {

    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final String variable;
    private final TreeMap<String, byte[]> files;
    private final long totalBytes;

    private RunDirectory(Builder builder) {
        this.variable = Objects.requireNonNull(builder.variable, "variable must not be null");
        if (!VARIABLE.matcher(variable).matches()) {
            throw new IllegalArgumentException("Not an environment variable name: '" + variable + "'");
        }
        this.files = new TreeMap<>(builder.files);
        long total = 0;
        for (byte[] bytes : files.values()) {
            total += bytes.length;
        }
        this.totalBytes = total;
    }

    /**
     * Returns the environment variable that holds the directory's absolute path while the command runs.
     *
     * @return the variable name ({@code [A-Za-z_][A-Za-z0-9_]*})
     */
    public String getVariable() {
        return variable;
    }

    /**
     * Returns the files to place, as paths relative to the directory.
     *
     * @return the paths, sorted and unmodifiable (may be empty: the directory is still created)
     */
    public Set<String> getPaths() {
        return Collections.unmodifiableSet(files.keySet());
    }

    /**
     * Returns the bytes of one file.
     *
     * @param relPath
     *            one of {@link #getPaths()}
     * @return a copy of the file's bytes
     * @throws IllegalArgumentException
     *             if the path is not one of {@link #getPaths()}
     */
    public byte[] getContent(String relPath) {
        final byte[] bytes = files.get(relPath);
        if (bytes == null) {
            throw new IllegalArgumentException("Not a file of this run directory: '" + relPath + "'");
        }
        return bytes.clone();
    }

    /**
     * Returns the size of all files together.
     *
     * @return the total in bytes
     */
    public long getTotalBytes() {
        return totalBytes;
    }

    /**
     * Returns a new builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "RunDirectory{variable=" + variable + ", files=" + files.size() + ", totalBytes=" + totalBytes + '}';
    }

    /** A relative path with no empty, {@code .} or {@code ..} segment and no backslash: it stays below its base. */
    private static boolean isConfinedRelativePath(String relPath) {
        if (relPath.isEmpty() || relPath.startsWith("/") || relPath.contains("\\") || relPath.indexOf('\0') >= 0) {
            return false;
        }
        return Arrays.stream(relPath.split("/", -1))
                .noneMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."));
    }

    /** Builder for {@link RunDirectory}. */
    public static final class Builder {
        private String variable;
        private final TreeMap<String, byte[]> files = new TreeMap<>();

        private Builder() {
        }

        /**
         * Sets the environment variable that names the directory.
         *
         * @param variable
         *            the variable name (required; {@code [A-Za-z_][A-Za-z0-9_]*})
         * @return this builder
         */
        public Builder variable(String variable) {
            this.variable = variable;
            return this;
        }

        /**
         * Adds one file. The bytes are copied.
         *
         * @param relPath
         *            the path relative to the directory: no leading {@code /}, no backslash, no empty, {@code .} or
         *            {@code ..} segment
         * @param bytes
         *            the content (must not be null)
         * @return this builder
         * @throws IllegalArgumentException
         *             if the path is not confined below the directory, or was already added
         */
        public Builder file(String relPath, byte[] bytes) {
            Objects.requireNonNull(relPath, "relPath must not be null");
            Objects.requireNonNull(bytes, "bytes must not be null");
            if (!isConfinedRelativePath(relPath)) {
                throw new IllegalArgumentException("Not a relative path inside the run directory: '" + relPath + "'");
            }
            if (files.putIfAbsent(relPath, bytes.clone()) != null) {
                throw new IllegalArgumentException("Added twice: '" + relPath + "'");
            }
            return this;
        }

        /**
         * Builds the run directory.
         *
         * @return the run directory
         * @throws NullPointerException
         *             if no variable was set
         * @throws IllegalArgumentException
         *             if the variable is not a valid name
         */
        public RunDirectory build() {
            return new RunDirectory(this);
        }
    }
}
