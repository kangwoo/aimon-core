package at.aimon.core.agent;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a semantic version (major.minor.patch[-qualifier]).
 *
 * <p>
 * This class follows the Semantic Versioning 2.0.0 specification.
 *
 * <p>
 * Immutable value object.
 *
 * <p>
 * Examples:
 * <ul>
 * <li>1.0.0</li>
 * <li>1.2.3</li>
 * <li>0.1.0-SNAPSHOT</li>
 * <li>2.0.0-beta.1</li>
 * </ul>
 */
public final class Version implements Comparable<Version> {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([\\w.]+))?$");

    private final int major;
    private final int minor;
    private final int patch;
    private final String qualifier;

    /**
     * Creates a new Version with the given components.
     *
     * @param major
     *            the major version number
     * @param minor
     *            the minor version number
     * @param patch
     *            the patch version number
     */
    public Version(int major, int minor, int patch) {
        this(major, minor, patch, null);
    }

    /**
     * Creates a new Version with the given components and qualifier.
     *
     * @param major
     *            the major version number
     * @param minor
     *            the minor version number
     * @param patch
     *            the patch version number
     * @param qualifier
     *            the optional version qualifier (e.g., "SNAPSHOT", "beta.1")
     */
    public Version(int major, int minor, int patch, String qualifier) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version numbers must be non-negative");
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.qualifier = qualifier;
    }

    /**
     * Parses a version string into a Version object.
     *
     * @param version
     *            the version string to parse (e.g., "1.2.3" or "1.2.3-SNAPSHOT")
     * @return the parsed Version object
     * @throws IllegalArgumentException
     *             if the version string is invalid
     */
    public static Version parse(String version) {
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalArgumentException("Version string cannot be null or empty");
        }

        final Matcher matcher = VERSION_PATTERN.matcher(version.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid version format: " + version);
        }

        final int major = Integer.parseInt(matcher.group(1));
        final int minor = Integer.parseInt(matcher.group(2));
        final int patch = Integer.parseInt(matcher.group(3));
        final String qualifier = matcher.group(4);

        return new Version(major, minor, patch, qualifier);
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public int getPatch() {
        return patch;
    }

    public String getQualifier() {
        return qualifier;
    }

    /**
     * Checks if this version is a snapshot version.
     *
     * @return true if this version has a qualifier containing "SNAPSHOT"
     */
    public boolean isSnapshot() {
        return qualifier != null && qualifier.toUpperCase().contains("SNAPSHOT");
    }

    /**
     * Checks if this version is a pre-release version.
     *
     * @return true if this version has any qualifier
     */
    public boolean isPreRelease() {
        return qualifier != null && !qualifier.isEmpty();
    }

    @Override
    public int compareTo(Version other) {
        if (other == null) {
            throw new NullPointerException("Cannot compare to null version");
        }

        // Compare major version
        int result = Integer.compare(major, other.major);
        if (result != 0) {
            return result;
        }

        // Compare minor version
        result = Integer.compare(minor, other.minor);
        if (result != 0) {
            return result;
        }

        // Compare patch version
        result = Integer.compare(patch, other.patch);
        if (result != 0) {
            return result;
        }

        // Compare qualifier (release versions > pre-release versions)
        if (qualifier == null && other.qualifier == null) {
            return 0;
        } else if (qualifier == null) {
            return 1; // release version is greater
        } else if (other.qualifier == null) {
            return -1; // pre-release version is less
        } else {
            return qualifier.compareTo(other.qualifier);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final Version other = (Version) obj;
        return major == other.major && minor == other.minor && patch == other.patch
                && Objects.equals(qualifier, other.qualifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, qualifier);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append(major).append('.').append(minor).append('.').append(patch);
        if (qualifier != null && !qualifier.isEmpty()) {
            sb.append('-').append(qualifier);
        }
        return sb.toString();
    }
}
