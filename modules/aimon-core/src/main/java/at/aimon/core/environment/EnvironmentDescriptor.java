package at.aimon.core.environment;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * How an {@link ExecutionEnvironment} describes itself to the model: where commands run and on what platform.
 *
 * <p>
 * Prompt assembly renders the descriptor of the execution's own environment, not the JVM host's values, so a shell
 * running in a Linux container is not described as the macOS host the agent happens to run on. Immutable.
 */
public final class EnvironmentDescriptor {

    /**
     * The runtime capability of a Python 3 interpreter on the shell's {@code PATH} as {@code python3}, version 3.8 or
     * later ({@code docs/design/tool/programmatic-tool-calling-shell.md} §10). The shell engine of {@code ExecuteCode}
     * needs it.
     */
    public static final String RUNTIME_PYTHON3 = "python3";

    private static final Pattern RUNTIME_NAME = Pattern.compile("[a-z0-9][a-z0-9._+-]*");

    private final String workingDirectory;
    private final String platform;
    private final String osVersion;
    private final String shellName;
    private final String notes;
    private final EnvironmentIsolation isolation;
    private final Set<String> runtimes;

    private EnvironmentDescriptor(Builder builder) {
        this.workingDirectory = Objects.requireNonNull(builder.workingDirectory, "workingDirectory must not be null");
        this.platform = builder.platform;
        this.osVersion = builder.osVersion;
        this.shellName = builder.shellName;
        this.notes = builder.notes;
        this.isolation = builder.isolation;
        this.runtimes = Collections.unmodifiableSet(new TreeSet<>(builder.runtimes));
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

    /**
     * Returns where this environment runs commands relative to the host — a static fact of the environment's profile.
     * {@code ExecuteCode} reads it at offer time to pick its engine ({@code programmatic-engine: auto}) without
     * provisioning anything.
     *
     * @return the isolation, empty when the environment does not declare it
     */
    public Optional<EnvironmentIsolation> isolation() {
        return Optional.ofNullable(isolation);
    }

    /**
     * Returns the language runtimes the environment's profile declares its shell can run ({@link #RUNTIME_PYTHON3}) — a
     * static fact like {@link #isolation()}, read at offer time without running anything. A runtime that is not
     * declared may still be installed; a caller that needs one does not count on it.
     *
     * @return the declared runtimes, sorted and unmodifiable (never null; empty when none is declared)
     */
    public Set<String> runtimes() {
        return runtimes;
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
                && Objects.equals(notes, that.notes) && isolation == that.isolation && runtimes.equals(that.runtimes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workingDirectory, platform, osVersion, shellName, notes, isolation, runtimes);
    }

    @Override
    public String toString() {
        return "EnvironmentDescriptor{workingDirectory='" + workingDirectory + "', platform=" + platform
                + ", osVersion=" + osVersion + ", shellName=" + shellName + ", notes=" + notes + ", isolation="
                + isolation + ", runtimes=" + runtimes + '}';
    }

    /** Builder for {@link EnvironmentDescriptor}. */
    public static final class Builder {
        private String workingDirectory;
        private String platform;
        private String osVersion;
        private String shellName;
        private String notes;
        private EnvironmentIsolation isolation;
        private final Set<String> runtimes = new TreeSet<>();

        private Builder() {
        }

        /**
         * Declares one language runtime the environment's shell can run ({@link #runtimes()}).
         *
         * @param runtime
         *            the runtime's name, lower case ({@link #RUNTIME_PYTHON3}, {@code node}, ...)
         * @return this builder
         * @throws IllegalArgumentException
         *             if the name is not a lower-case runtime name
         */
        public Builder runtime(String runtime) {
            Objects.requireNonNull(runtime, "runtime must not be null");
            if (!RUNTIME_NAME.matcher(runtime).matches()) {
                throw new IllegalArgumentException("Not a runtime name: '" + runtime + "'");
            }
            runtimes.add(runtime);
            return this;
        }

        /**
         * Replaces the declared runtimes ({@link #runtimes()}).
         *
         * @param runtimes
         *            the runtimes' names (must not be null)
         * @return this builder
         * @throws IllegalArgumentException
         *             if a name is not a lower-case runtime name
         */
        public Builder runtimes(Collection<String> runtimes) {
            Objects.requireNonNull(runtimes, "runtimes must not be null");
            this.runtimes.clear();
            runtimes.forEach(this::runtime);
            return this;
        }

        /**
         * @param isolation
         *            where the environment runs commands, or null if it does not declare it
         * @return this builder
         */
        public Builder isolation(EnvironmentIsolation isolation) {
            this.isolation = isolation;
            return this;
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
