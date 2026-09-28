package at.aimon.core.environment;

import java.util.Objects;
import java.util.Optional;

/**
 * How an {@link ExecutionEnvironment} describes itself to the model: where commands run and on what platform.
 *
 * <p>
 * Prompt assembly renders the descriptor of the execution's own environment, not the JVM host's values, so a shell
 * running in a Linux container is not described as the macOS host the agent happens to run on. Immutable.
 */
public final class EnvironmentDescriptor {

    private final String workingDirectory;
    private final String platform;
    private final String osVersion;
    private final String shellName;
    private final String notes;

    private EnvironmentDescriptor(Builder builder) {
        this.workingDirectory = Objects.requireNonNull(builder.workingDirectory, "workingDirectory must not be null");
        this.platform = builder.platform;
        this.osVersion = builder.osVersion;
        this.shellName = builder.shellName;
        this.notes = builder.notes;
    }

    /**
     * The descriptor of an environment that could not be resolved. Every field is empty except {@link #notes()},
     * which carries the cause, so the model learns why before its first tool call.
     *
     * @param cause
     *            a short description of why the environment is unavailable (must not be null)
     * @return the descriptor
     */
    public static EnvironmentDescriptor unavailable(String cause) {
        Objects.requireNonNull(cause, "cause must not be null");
        return builder().workingDirectory("").notes("execution environment unavailable: " + cause).build();
    }

    /**
     * Returns the directory commands and relative file-tool paths resolve against.
     *
     * @return the working directory (never null; empty for an unavailable environment)
     */
    public String workingDirectory() {
        return workingDirectory;
    }

    /** @return the platform name ({@code darwin}, {@code linux}, {@code windows}, ...), if known */
    public Optional<String> platform() {
        return Optional.ofNullable(platform);
    }

    /** @return the operating system version, if known */
    public Optional<String> osVersion() {
        return Optional.ofNullable(osVersion);
    }

    /** @return the shell commands run in ({@code bash}, {@code cmd}, ...), if known */
    public Optional<String> shellName() {
        return Optional.ofNullable(shellName);
    }

    /** @return free text of a line or two about the environment (e.g. "isolated sandbox; network restricted") */
    public Optional<String> notes() {
        return Optional.ofNullable(notes);
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EnvironmentDescriptor that)) {
            return false;
        }
        return workingDirectory.equals(that.workingDirectory) && Objects.equals(platform, that.platform)
                && Objects.equals(osVersion, that.osVersion) && Objects.equals(shellName, that.shellName)
                && Objects.equals(notes, that.notes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workingDirectory, platform, osVersion, shellName, notes);
    }

    @Override
    public String toString() {
        return "EnvironmentDescriptor{workingDirectory='" + workingDirectory + "', platform=" + platform
                + ", osVersion=" + osVersion + ", shellName=" + shellName + ", notes=" + notes + '}';
    }

    /** Builder for {@link EnvironmentDescriptor}. */
    public static final class Builder {
        private String workingDirectory;
        private String platform;
        private String osVersion;
        private String shellName;
        private String notes;

        private Builder() {
        }

        /**
         * @param workingDirectory
         *            the working directory (required)
         * @return this builder
         */
        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * @param platform
         *            the platform name, or null if unknown
         * @return this builder
         */
        public Builder platform(String platform) {
            this.platform = platform;
            return this;
        }

        /**
         * @param osVersion
         *            the OS version, or null if unknown
         * @return this builder
         */
        public Builder osVersion(String osVersion) {
            this.osVersion = osVersion;
            return this;
        }

        /**
         * @param shellName
         *            the shell name, or null if unknown
         * @return this builder
         */
        public Builder shellName(String shellName) {
            this.shellName = shellName;
            return this;
        }

        /**
         * @param notes
         *            free-text notes, or null for none
         * @return this builder
         */
        public Builder notes(String notes) {
            this.notes = notes;
            return this;
        }

        /** @return the descriptor */
        public EnvironmentDescriptor build() {
            return new EnvironmentDescriptor(this);
        }
    }
}
