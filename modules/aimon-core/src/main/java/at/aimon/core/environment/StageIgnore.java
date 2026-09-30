package at.aimon.core.environment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A minimal gitignore-subset matcher for {@code .stageignore} files (design §4.4): globs ({@code *}, {@code **},
 * {@code ?}), directory patterns ({@code dir/}), anchored patterns (containing a {@code /}), {@code !} negation and
 * {@code #} comments. The last matching rule wins, and ignoring a directory ignores everything under it.
 *
 * <p>
 * Both local and sandbox providers honour {@code .stageignore}, so the matcher belongs to the SPI rather than to any
 * one provider or to the skill package.
 */
public final class StageIgnore {

    private static final StageIgnore NONE = new StageIgnore(List.of());

    private final List<Rule> rules;

    private StageIgnore(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** @return a matcher that ignores nothing */
    public static StageIgnore none() {
        return NONE;
    }

    /**
     * Parses {@code .stageignore} content.
     *
     * @param content
     *            the file content (must not be null)
     * @return the matcher
     */
    public static StageIgnore parse(String content) {
        final List<Rule> rules = new ArrayList<>();
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            boolean negated = false;
            if (line.startsWith("!")) {
                negated = true;
                line = line.substring(1);
            }
            boolean directoryOnly = false;
            while (line.endsWith("/")) {
                directoryOnly = true;
                line = line.substring(0, line.length() - 1);
            }
            final boolean anchored = line.contains("/");
            while (line.startsWith("/")) {
                line = line.substring(1);
            }
            if (line.isEmpty()) {
                continue;
            }
            rules.add(new Rule(Pattern.compile(globToRegex(line)), negated, directoryOnly, anchored));
        }
        return rules.isEmpty() ? NONE : new StageIgnore(rules);
    }

    /**
     * Whether a file is excluded from staging.
     *
     * @param relPath
     *            the file path relative to the resource directory
     * @return {@code true} if the file is ignored
     */
    public boolean ignored(String relPath) {
        if (rules.isEmpty()) {
            return false;
        }
        final String[] segments = relPath.split("/");
        boolean ignored = false;
        for (Rule rule : rules) {
            if (rule.matches(segments)) {
                ignored = !rule.negated;
            }
        }
        return ignored;
    }

    private static String globToRegex(String glob) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            final char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        sb.append("(?:.*/)?");
                    } else {
                        sb.append(".*");
                    }
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return sb.toString();
    }

    private static final class Rule {
        private final Pattern pattern;
        private final boolean negated;
        private final boolean directoryOnly;
        private final boolean anchored;

        Rule(Pattern pattern, boolean negated, boolean directoryOnly, boolean anchored) {
            this.pattern = pattern;
            this.negated = negated;
            this.directoryOnly = directoryOnly;
            this.anchored = anchored;
        }

        /** Matches the path itself or any of its ancestor directories. */
        boolean matches(String[] segments) {
            final StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) {
                    prefix.append('/');
                }
                prefix.append(segments[i]);
                final boolean isDirectory = i < segments.length - 1;
                if (directoryOnly && !isDirectory) {
                    continue;
                }
                final String candidate = anchored ? prefix.toString() : segments[i];
                if (pattern.matcher(candidate).matches()) {
                    return true;
                }
            }
            return false;
        }
    }
}
