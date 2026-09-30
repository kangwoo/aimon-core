package at.aimon.core.environment;

import java.util.List;
import java.util.Objects;

/**
 * The normalised answer to a {@link ContentQuery}: for each file with at least one match, its path (as the
 * environment's filesystem would list it) and its matching lines with their context.
 */
public final class ContentSearchResult {

    private final List<FileMatches> files;

    private ContentSearchResult(List<FileMatches> files) {
        this.files = List.copyOf(files);
    }

    /**
     * @param files
     *            the per-file matches (must not be null)
     * @return the result
     */
    public static ContentSearchResult of(List<FileMatches> files) {
        return new ContentSearchResult(Objects.requireNonNull(files, "files must not be null"));
    }

    /** @return the files with matches */
    public List<FileMatches> getFiles() {
        return files;
    }

    /** The matches of one file. */
    public static final class FileMatches {
        private final String path;
        private final List<Match> matches;

        private FileMatches(String path, List<Match> matches) {
            this.path = Objects.requireNonNull(path, "path must not be null");
            this.matches = List.copyOf(matches);
        }

        /**
         * @param path
         *            the file path
         * @param matches
         *            its matches
         * @return the value
         */
        public static FileMatches of(String path, List<Match> matches) {
            return new FileMatches(path, matches);
        }

        /** @return the file path */
        public String getPath() {
            return path;
        }

        /** @return the matches, in line order */
        public List<Match> getMatches() {
            return matches;
        }
    }

    /** One matching line and its context. */
    public static final class Match {
        private final int lineNumber;
        private final String line;
        private final List<String> before;
        private final List<String> after;

        private Match(int lineNumber, String line, List<String> before, List<String> after) {
            this.lineNumber = lineNumber;
            this.line = Objects.requireNonNull(line, "line must not be null");
            this.before = List.copyOf(before);
            this.after = List.copyOf(after);
        }

        /**
         * @param lineNumber
         *            the 1-based line number
         * @param line
         *            the line text
         * @param before
         *            context lines before it
         * @param after
         *            context lines after it
         * @return the value
         */
        public static Match of(int lineNumber, String line, List<String> before, List<String> after) {
            return new Match(lineNumber, line, before, after);
        }

        /** @return the 1-based line number */
        public int getLineNumber() {
            return lineNumber;
        }

        /** @return the line text */
        public String getLine() {
            return line;
        }

        /** @return context lines before the match (possibly empty) */
        public List<String> getBefore() {
            return before;
        }

        /** @return context lines after the match (possibly empty) */
        public List<String> getAfter() {
            return after;
        }
    }
}
