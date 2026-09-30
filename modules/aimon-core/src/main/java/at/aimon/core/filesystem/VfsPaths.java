package at.aimon.core.filesystem;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Path helpers shared by every piece of code that has to agree on what one VFS path "is": the worktree scoping
 * decorator, the path-rule decorator and the file tools' read stamps. Keeping one rule here is what stops
 * {@code a.txt}, {@code ./a.txt} and an absolute path under the base from being three different keys in one place and
 * one key in another.
 */
public final class VfsPaths {

    private VfsPaths() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Returns the VFS-root-relative, normalised form of a caller path.
     *
     * <ul>
     * <li>For a {@code '/'}-anchored working directory (a local base such as {@code /proj}), an absolute path under it
     * has the base stripped; an absolute path <em>outside</em> it is returned unchanged (normalised), since no
     * root-relative form exists.</li>
     * <li>For a URI-shaped working directory ({@code gridfs://…}, {@code s3://…}) or a relative one ({@code "."}),
     * leading slashes are stripped — those backends treat a leading slash as anchored on their root. A relative
     * {@code "."} base never treats a {@code '/'}-leading path as relative, though: it is returned unchanged.</li>
     * <li>In every case {@code .} and {@code ..} segments are resolved.</li>
     * </ul>
     *
     * @param workingDirectory
     *            the filesystem's (or environment's) working directory
     * @param path
     *            the caller path
     * @return the normalised root-relative path ({@code ""} for the root), or the normalised absolute path when it lies
     *         outside a {@code '/'}-anchored base; {@code null} if a relative path escapes above the root
     */
    public static String rootRelative(String workingDirectory, String path) {
        if (path == null) {
            return "";
        }
        final String p = path.replace('\\', '/');
        final String base = workingDirectory == null ? "" : trimTrailingSlashes(workingDirectory);
        if (base.startsWith("/") || isRelativeBase(base)) {
            if (base.startsWith("/") && !base.equals("/") && !base.isEmpty()) {
                if (p.equals(base)) {
                    return "";
                }
                if (p.startsWith(base + "/")) {
                    return normalizeRelative(p.substring(base.length() + 1));
                }
            }
            if (p.startsWith("/")) {
                if (base.equals("/")) {
                    return normalizeRelative(p);
                }
                final String normalized = normalizeRelative(p);
                return normalized == null ? null : "/" + normalized;
            }
            return normalizeRelative(p);
        }
        return normalizeRelative(p);
    }

    /**
     * Resolves a caller path the way a base-anchored backend does — a relative path against the working directory, then
     * {@code .} and {@code ..} segments — and returns where it lands relative to that base. Unlike
     * {@link #rootRelative}, a path that leaves the base and comes back ({@code ../proj/x} or {@code /proj/../proj/x}
     * for a base {@code /proj}) resolves to its in-base form ({@code x}), so an access rule cannot be bypassed by the
     * spelling of the path.
     *
     * <ul>
     * <li>For a {@code '/'}- or drive-anchored ({@code C:/…}) working directory, the result is {@code null} when the
     * path lands outside it.</li>
     * <li>For a URI-shaped or relative working directory, leading slashes are treated as anchored on the root, and the
     * result is {@code null} when {@code ..} climbs above the root.</li>
     * </ul>
     *
     * @param workingDirectory
     *            the filesystem's working directory
     * @param path
     *            the caller path
     * @return the normalised base-relative path ({@code ""} for the base itself), or {@code null} if the path does not
     *         land under the base
     */
    public static String resolveUnder(String workingDirectory, String path) {
        if (path == null) {
            return "";
        }
        final String p = path.replace('\\', '/');
        final String rawBase = workingDirectory == null ? "" : workingDirectory.replace('\\', '/');
        if (!isAnchored(rawBase)) {
            return normalizeRelative(p);
        }
        final String normalizedBase = normalizeRelative(rawBase);
        if (normalizedBase == null) {
            return null;
        }
        final String absolute = normalizeRelative(isAnchored(p) ? p : rawBase + "/" + p);
        if (absolute == null) {
            return null;
        }
        if (normalizedBase.isEmpty() || absolute.equals(normalizedBase)) {
            return normalizedBase.isEmpty() ? absolute : "";
        }
        return absolute.startsWith(normalizedBase + "/") ? absolute.substring(normalizedBase.length() + 1) : null;
    }

    /**
     * Resolves {@code .} and {@code ..} segments of a path and drops empty segments (so leading, trailing and doubled
     * slashes disappear).
     *
     * @param path
     *            the path
     * @return the normalised relative path ({@code ""} for the root), or {@code null} if it escapes above its root
     */
    public static String normalizeRelative(String path) {
        final Deque<String> out = new ArrayDeque<>();
        for (final String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (out.isEmpty()) {
                    return null;
                }
                out.removeLast();
            } else {
                out.addLast(segment);
            }
        }
        return String.join("/", out);
    }

    /**
     * Whether a normalised root-relative path is the prefix directory itself or lies under it, matching whole
     * segments only ({@code .aimon-staged2/x} is not under {@code .aimon-staged}).
     *
     * @param rootRelative
     *            a normalised root-relative path
     * @param prefix
     *            a normalised root-relative directory
     * @return {@code true} if the path is at or under the prefix
     */
    public static boolean isUnder(String rootRelative, String prefix) {
        if (rootRelative == null || prefix == null || prefix.isEmpty()) {
            return false;
        }
        return rootRelative.equals(prefix) || rootRelative.startsWith(prefix + "/");
    }

    /**
     * Like {@link #isUnder}, but compares {@linkplain #foldCase folded} names, so {@code .AIMON/x} and
     * {@code .aimon-ſtaged/x} (U+017F LATIN SMALL LETTER LONG S) are under {@code .aimon} and {@code .aimon-staged}.
     * Access rules use this: on a case-insensitive store (APFS, NTFS) such spellings name the same directory, and on a
     * case-sensitive one hiding a differently spelled twin is the safe direction.
     *
     * @param rootRelative
     *            a normalised root-relative path
     * @param prefix
     *            a normalised root-relative directory
     * @return {@code true} if the path is at or under the prefix, ignoring case
     */
    public static boolean isUnderIgnoreCase(String rootRelative, String prefix) {
        if (rootRelative == null || prefix == null) {
            return false;
        }
        return isUnder(foldCase(rootRelative), foldCase(prefix));
    }

    /**
     * Folds a path for a case-insensitive comparison that is at least as aggressive as the stores it guards: NFKC
     * (compatibility forms such as {@code ſ} → {@code s} and the {@code ﬅ} ligature → {@code st}), then a full case
     * fold as lower-, upper- and lower-casing ({@code ß} → {@code ss}, the Kelvin sign → {@code k}), then NFC again.
     * The first lower-casing is what folds an uppercase letter whose full fold expands: {@code ẞ} (U+1E9E) is already
     * uppercase, so upper-then-lower would stop at {@code ß}, while lower-upper-lower gives {@code ß}, {@code SS},
     * {@code ss} — as APFS does. Folding more than a store does only hides more, which is the direction an access rule
     * may err in.
     *
     * @param path
     *            a path
     * @return the folded path
     */
    static String foldCase(String path) {
        final String compatible = Normalizer.normalize(path, Normalizer.Form.NFKC);
        final String folded = compatible.toLowerCase(Locale.ROOT).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
        return Normalizer.normalize(folded, Normalizer.Form.NFC);
    }

    /**
     * Joins path parts with single slashes, keeping a leading {@code '/'} or URI scheme of the first part.
     *
     * @param first
     *            the first part (may be absolute or URI-shaped)
     * @param more
     *            further relative parts
     * @return the joined path
     */
    public static String join(String first, String... more) {
        final StringBuilder sb = new StringBuilder(trimTrailingSlashes(first));
        for (String part : more) {
            final String trimmed = trimTrailingSlashes(stripLeadingSlashes(part));
            if (trimmed.isEmpty() || trimmed.equals(".")) {
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') {
                sb.append('/');
            }
            sb.append(trimmed);
        }
        return sb.toString();
    }

    private static boolean isAnchored(String path) {
        return path.startsWith("/") || path.length() >= 3 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':'
                && path.charAt(2) == '/';
    }

    private static boolean isRelativeBase(String base) {
        return base.isEmpty() || base.equals(".");
    }

    private static String stripLeadingSlashes(String p) {
        int start = 0;
        while (start < p.length() && p.charAt(start) == '/') {
            start++;
        }
        return p.substring(start);
    }

    private static String trimTrailingSlashes(String p) {
        String s = p;
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
