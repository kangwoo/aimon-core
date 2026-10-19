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
 *
 * <p>
 * The builder refuses what no shell could place as given: a path with a control character, a path that is both a
 * file and the parent directory of another, and more than {@link #MAX_TOTAL_BYTES} in all. A shell that declares
 * {@link ShellFeature#RUN_DIRECTORY} can therefore rely on those three without checking them.
 */
public final class RunDirectory {

    /**
     * The most a run directory holds, summed over its files: 4 MiB. The builder refuses more, so a shell that declares
     * {@link ShellFeature#RUN_DIRECTORY} can rely on it and size its transfer for it. It is written, and on a remote
     * shell uploaded, at every hook firing.
     *
     * <p>
     * A shell that compiles this constant in and keeps its own check refuses anything above the value it was built
     * against, so raising it is a contract change: it ships only after every shell that checks the cap has been rebuilt
     * and released against the new value. Lowering it only makes the builder refuse earlier.
     */
    public static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024;

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
        if (relPath.isEmpty() || relPath.startsWith("/") || relPath.contains("\\")) {
            return false;
        }
        return Arrays.stream(relPath.split("/", -1))
                .noneMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."));
    }

    /**
     * The first {@linkplain Character#isISOControl(char) ISO control character} in the path, or {@code -1}. Every one
     * is in the BMP, so a per-{@code char} scan is exact.
     */
    private static int firstControlCharacter(String relPath) {
        for (int i = 0; i < relPath.length(); i++) {
            if (Character.isISOControl(relPath.charAt(i))) {
                return relPath.charAt(i);
            }
        }
        return -1;
    }

    /**
     * The path with each control character written as <code>&#92;uXXXX</code>, safe for a log line or a deny reason.
     */
    private static String escaped(String relPath) {
        final StringBuilder out = new StringBuilder(relPath.length());
        for (int i = 0; i < relPath.length(); i++) {
            final char c = relPath.charAt(i);
            if (Character.isISOControl(c)) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Builder for {@link RunDirectory}. */
    public static final class Builder {
        private String variable;
        private final TreeMap<String, byte[]> files = new TreeMap<>();
        private long totalBytes;

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
         * Adds one file. The bytes are copied. A refused call leaves the builder as it was.
         *
         * <p>
         * The control characters refused are exactly those of {@link Character#isISOControl(int)}: the code points
         * U+0000&ndash;U+001F (newline, carriage return and tab among them), U+007F and U+0080&ndash;U+009F (in UTF-8,
         * the two-byte sequences {@code C2 80}&ndash;{@code C2 9F}). A line-based digest manifest cannot carry a
         * newline, and the rest reach log lines and deny reasons a terminal shows. The exception message writes them as
         * <code>&#92;uXXXX</code>, never raw.
         *
         * @param relPath
         *            the path relative to the directory: no leading {@code /}, no backslash, no empty, {@code .} or
         *            {@code ..} segment, no control character
         * @param bytes
         *            the content (must not be null)
         * @return this builder
         * @throws IllegalArgumentException
         *             if the path holds a control character, is not confined below the directory, was already added,
         *             is the parent directory of a path already added or has one of them as its parent directory, or
         *             if the file would take the directory over {@link #MAX_TOTAL_BYTES}
         */
        public Builder file(String relPath, byte[] bytes) {
            Objects.requireNonNull(relPath, "relPath must not be null");
            Objects.requireNonNull(bytes, "bytes must not be null");
            // First, so that no later message prints a control character raw.
            final int control = firstControlCharacter(relPath);
            if (control >= 0) {
                throw new IllegalArgumentException("Not placeable in a run directory: '" + escaped(relPath)
                        + "' contains a control character (" + String.format("U+%04X", control) + ")");
            }
            if (!isConfinedRelativePath(relPath)) {
                throw new IllegalArgumentException("Not a relative path inside the run directory: '" + relPath + "'");
            }
            if (files.containsKey(relPath)) {
                throw new IllegalArgumentException("Added twice: '" + relPath + "'");
            }
            for (int slash = relPath.indexOf('/'); slash >= 0; slash = relPath.indexOf('/', slash + 1)) {
                final String parent = relPath.substring(0, slash);
                if (files.containsKey(parent)) {
                    throw overlap(parent, relPath);
                }
            }
            // Every path below relPath sorts at or after relPath + "/", so the first of them is that key's ceiling.
            final String below = files.ceilingKey(relPath + "/");
            if (below != null && below.startsWith(relPath + "/")) {
                throw overlap(relPath, below);
            }
            if (totalBytes + bytes.length > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("Not placeable in a run directory: '" + relPath
                        + "' would make it hold " + (totalBytes + bytes.length) + " bytes, over the " + MAX_TOTAL_BYTES
                        + "-byte limit of a run directory");
            }
            files.put(relPath, bytes.clone());
            totalBytes += bytes.length;
            return this;
        }

        private static IllegalArgumentException overlap(String parent, String child) {
            return new IllegalArgumentException("Not placeable in a run directory: '" + parent
                    + "' is a file and also the parent directory of '" + child + "'");
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
