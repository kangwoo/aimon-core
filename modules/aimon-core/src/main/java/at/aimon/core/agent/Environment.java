package at.aimon.core.agent;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;

/**
 * Represents the runtime environment information. This class captures system-level details such as working directory,
 * platform, OS version, and time zone.
 *
 * <p>
 * Instances of this class are immutable and thread-safe.
 */
public final class Environment {

    private final String workingDirectory;
    private final String platform;
    private final String osVersion;
    private final ZoneId timeZone;

    private Environment(Builder builder) {
        workingDirectory = Objects.requireNonNull(builder.workingDirectory, "workingDirectory must not be null");
        platform = Objects.requireNonNull(builder.platform, "platform must not be null");
        osVersion = Objects.requireNonNull(builder.osVersion, "osVersion must not be null");
        timeZone = Objects.requireNonNull(builder.timeZone, "timeZone must not be null");
    }

    /**
     * Creates a new Environment instance with current system information. The working directory is derived from the
     * {@code user.dir} system property.
     *
     * <p>
     * <b>Embedded / containerized hosts should not use this factory.</b> Inside a container (or any embedding host
     * that boots the agent from an arbitrary process working directory), {@code user.dir} is the JVM's launch
     * directory, which is rarely the agent workspace. Such hosts should call
     * {@link #createWithWorkingDirectory(String)} &mdash; or {@link #builder()} &mdash; with an explicitly configured
     * path instead. The {@code user.dir} default is kept as a documented fallback for CLI / desktop use.
     *
     * @return Environment instance populated with current system properties
     */
    public static Environment createDefault() {
        return createWithWorkingDirectory(System.getProperty("user.dir"));
    }

    /**
     * Creates a new Environment instance with working directory set to the specified path. The remaining fields
     * (platform, OS version, time zone) are still derived from the running system, which is safe because they
     * describe the host the agent actually executes on.
     *
     * <p>
     * This is the factory embedded and containerized hosts should use: it takes the working directory explicitly
     * rather than deriving it from the unreliable {@code user.dir} system property. The in-tree runtime already goes
     * through here &mdash; {@code OrcaAgentRuntimeFactory} feeds it {@code VirtualFileSystem#getWorkingDirectory()},
     * so the working directory comes from the injected filesystem rather than from the JVM's launch directory.
     *
     * @param workingDirectory
     *            the working directory path
     * @return Environment instance with specified working directory and other system properties
     */
    public static Environment createWithWorkingDirectory(String workingDirectory) {
        return builder().workingDirectory(workingDirectory).platform(detectPlatform())
                .osVersion(System.getProperty("os.version")).timeZone(ZoneId.systemDefault()).build();
    }

    /**
     * Detects the platform name from system properties. Normalizes OS names to common identifiers: darwin, linux,
     * windows.
     *
     * @return normalized platform name
     */
    private static String detectPlatform() {
        final String osName = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);

        if (osName.contains("mac") || osName.contains("darwin")) {
            return "darwin";
        } else if (osName.contains("win")) {
            return "windows";
        } else if (osName.contains("nux") || osName.contains("nix") || osName.contains("aix")) {
            return "linux";
        } else {
            return osName;
        }
    }

    /**
     * Gets the working directory.
     *
     * @return current working directory path
     */
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * Gets the platform name (e.g., darwin, linux, windows).
     *
     * @return platform name
     */
    public String getPlatform() {
        return platform;
    }

    /**
     * Gets the OS version string.
     *
     * @return OS version
     */
    public String getOsVersion() {
        return osVersion;
    }

    /**
     * Gets the time zone.
     *
     * @return time zone
     */
    public ZoneId getTimeZone() {
        return timeZone;
    }

    /**
     * Creates a new builder instance.
     *
     * @return new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for Environment instances. */
    public static final class Builder {
        private String workingDirectory;
        private String platform;
        private String osVersion;
        private ZoneId timeZone;

        private Builder() {
        }

        /**
         * Sets the working directory from a string path.
         *
         * @param workingDirectory
         *            working directory path as string
         * @return this builder
         */
        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * Sets the platform name.
         *
         * @param platform
         *            platform name (e.g., darwin, linux, windows)
         * @return this builder
         */
        public Builder platform(String platform) {
            this.platform = platform;
            return this;
        }

        /**
         * Sets the OS version.
         *
         * @param osVersion
         *            OS version string
         * @return this builder
         */
        public Builder osVersion(String osVersion) {
            this.osVersion = osVersion;
            return this;
        }

        /**
         * Sets the time zone.
         *
         * @param timeZone
         *            time zone
         * @return this builder
         */
        public Builder timeZone(ZoneId timeZone) {
            this.timeZone = timeZone;
            return this;
        }

        /**
         * Builds the Environment instance.
         *
         * @return new Environment instance
         * @throws NullPointerException
         *             if any required field is null
         */
        public Environment build() {
            return new Environment(this);
        }
    }

    @Override
    public String toString() {
        return "Environment{" + "workingDirectory=" + workingDirectory + ", platform='" + platform + '\''
                + ", osVersion='" + osVersion + '\'' + ", timeZone=" + timeZone + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Environment that = (Environment) o;
        return Objects.equals(workingDirectory, that.workingDirectory) && Objects.equals(platform, that.platform)
                && Objects.equals(osVersion, that.osVersion) && Objects.equals(timeZone, that.timeZone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workingDirectory, platform, osVersion, timeZone);
    }
}
