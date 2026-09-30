package at.aimon.core.shell;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration options for shell command execution.
 *
 * <p>
 * This immutable value object encapsulates various execution parameters such as timeout, environment variables, working
 * directory, character encoding, and shell-specific settings.
 *
 * <p>
 * Use the {@link Builder} to construct instances with custom options:
 *
 * <pre>
 * {
 *     &#64;code
 *     ExecutionOptions options = ExecutionOptions.builder().timeout(Duration.ofSeconds(30)).workingDirectory("/tmp")
 *             .environment(Map.of("PATH", "/usr/bin")).build();
 * }
 * </pre>
 */
public final class ExecutionOptions {

    private final Duration timeout;
    private final Map<String, String> environment;
    private final String workingDirectory;
    private final Charset charset;
    private final boolean redirectErrorStream;
    private final String unixShell;
    private final Long maxCaptureBytes;
    private final String stdin;
    private final boolean background;

    private ExecutionOptions(Builder builder) {
        this.timeout = builder.timeout;
        this.environment = builder.environment == null ? Map.of() : Map.copyOf(builder.environment);
        this.workingDirectory = builder.workingDirectory;
        this.charset = Objects.requireNonNull(builder.charset, "charset");
        this.redirectErrorStream = builder.redirectErrorStream;
        this.unixShell = builder.unixShell;
        this.maxCaptureBytes = builder.maxCaptureBytes;
        this.stdin = builder.stdin;
        this.background = builder.background;
    }

    /**
     * Returns default execution options.
     *
     * <p>
     * Default configuration:
     * <ul>
     * <li>No timeout (command runs until completion)
     * <li>Empty environment (inherits from parent process)
     * <li>No working directory override (uses shell default)
     * <li>UTF-8 charset
     * <li>Error stream not redirected (stderr separate from stdout)
     * <li>No Unix shell override (uses shell default, typically "bash")
     * <li>No capture-limit override (implementation default is used)
     * </ul>
     *
     * @return default execution options
     */
    public static ExecutionOptions defaults() {
        return builder().build();
    }

    /**
     * Returns a new builder for constructing execution options.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the timeout duration for command execution.
     *
     * @return the timeout, or null if no timeout is set
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Returns the environment variables to be set for command execution.
     *
     * @return immutable map of environment variables, never null (may be empty)
     */
    public Map<String, String> getEnvironment() {
        return environment;
    }

    /**
     * Returns the working directory for command execution.
     *
     * @return the working directory path, or null to use shell default
     */
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * Returns the character encoding for command I/O.
     *
     * @return the charset, never null
     */
    public Charset getCharset() {
        return charset;
    }

    /**
     * Returns whether stderr should be redirected to stdout.
     *
     * @return true if error stream is merged into output stream
     */
    public boolean isRedirectErrorStream() {
        return redirectErrorStream;
    }

    /**
     * Returns the Unix shell to use for command execution.
     *
     * @return the Unix shell path/name (e.g., "bash", "sh", "zsh"), or null to use default
     */
    public String getUnixShell() {
        return unixShell;
    }

    /**
     * Returns the maximum number of bytes to read from each captured stream (stdout/stderr) into the result.
     *
     * <p>
     * The child always writes its full output to the capture file; this only bounds how much is decoded into memory, so
     * a runaway command cannot exhaust the heap. When a stream exceeds this limit the extra is dropped and the result
     * is
     * marked {@link ShellCommandResult#outputTruncated() truncated}.
     *
     * @return the per-stream capture limit in bytes, or null to use the implementation default
     */
    public Long getMaxCaptureBytes() {
        return maxCaptureBytes;
    }

    /**
     * Returns the text fed to the command's standard input.
     *
     * <p>
     * When null the child inherits whatever the implementation's default is. {@code LocalShell} redirects stdin from
     * an empty source in that case, so the child reads EOF on its first read rather than blocking on a pipe nothing
     * ever writes to. When non-null the implementation must deliver exactly this text, encoded with
     * {@link #getCharset()}, followed by EOF. Either way a command that reads stdin to exhaustion must terminate.
     *
     * @return the stdin payload, or null to leave stdin at the implementation default
     */
    public String getStdin() {
        return stdin;
    }

    /**
     * Whether the command was started in the background ({@code Bash(run_in_background=true)}) and may outlive the
     * call that started it.
     *
     * <p>
     * A shell that runs one command at a time in a persistent session (a sandbox) must not let such a command hold
     * that session, or every later command in it waits; it runs it as a one-shot instead. A shell that starts a new
     * process per command — {@code LocalShell} — ignores the flag (execution-environment design §5.3).
     *
     * @return {@code true} for a background command
     */
    public boolean isBackground() {
        return background;
    }

    /**
     * Returns a builder seeded with every option of this instance, for deriving a variant.
     *
     * @return a pre-populated builder
     */
    public Builder toBuilder() {
        final Builder builder = new Builder().timeout(timeout).environment(environment)
                .workingDirectory(workingDirectory).charset(charset).redirectErrorStream(redirectErrorStream)
                .unixShell(unixShell).stdin(stdin).background(background);
        if (maxCaptureBytes != null) {
            builder.maxCaptureBytes(maxCaptureBytes);
        }
        return builder;
    }

    /**
     * Builder for constructing {@link ExecutionOptions} instances.
     */
    public static final class Builder {
        private Duration timeout;
        private Map<String, String> environment = Map.of();
        private String workingDirectory;
        private Charset charset = StandardCharsets.UTF_8;
        private boolean redirectErrorStream = false;
        private String unixShell;
        private Long maxCaptureBytes;
        private String stdin;
        private boolean background;

        private Builder() {
        }

        /**
         * Sets the timeout for command execution.
         *
         * @param timeout
         *            the timeout duration, or null for no timeout
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets environment variables for command execution.
         *
         * @param environment
         *            the environment variables, or null for empty environment
         * @return this builder
         */
        public Builder environment(Map<String, String> environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Sets the working directory for command execution.
         *
         * @param workingDirectory
         *            the working directory path, or null to use shell default
         * @return this builder
         */
        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * Sets the character encoding for command I/O.
         *
         * @param charset
         *            the charset, must not be null
         * @return this builder
         * @throws NullPointerException
         *             if charset is null
         */
        public Builder charset(Charset charset) {
            this.charset = Objects.requireNonNull(charset, "charset");
            return this;
        }

        /**
         * Sets whether stderr should be redirected to stdout.
         *
         * @param redirectErrorStream
         *            true to merge error stream into output stream
         * @return this builder
         */
        public Builder redirectErrorStream(boolean redirectErrorStream) {
            this.redirectErrorStream = redirectErrorStream;
            return this;
        }

        /**
         * Sets the Unix shell to use for command execution.
         *
         * @param unixShell
         *            the Unix shell path/name (e.g., "bash", "sh", "zsh"), or null to use default
         * @return this builder
         */
        public Builder unixShell(String unixShell) {
            this.unixShell = unixShell;
            return this;
        }

        /**
         * Sets the maximum number of bytes to read from each captured stream into the result.
         *
         * @param maxCaptureBytes
         *            the per-stream capture limit in bytes (must be {@code >= 0})
         * @return this builder
         * @throws IllegalArgumentException
         *             if {@code maxCaptureBytes} is negative
         * @see ExecutionOptions#getMaxCaptureBytes()
         */
        public Builder maxCaptureBytes(long maxCaptureBytes) {
            if (maxCaptureBytes < 0) {
                throw new IllegalArgumentException("maxCaptureBytes must be >= 0, got: " + maxCaptureBytes);
            }
            this.maxCaptureBytes = maxCaptureBytes;
            return this;
        }

        /**
         * Sets the text fed to the command's standard input.
         *
         * @param stdin
         *            the stdin payload, or null to leave stdin at the implementation default
         * @return this builder
         * @see ExecutionOptions#getStdin()
         */
        public Builder stdin(String stdin) {
            this.stdin = stdin;
            return this;
        }

        /**
         * Marks the command as started in the background (default {@code false}).
         *
         * @param background
         *            whether the command runs in the background
         * @return this builder
         * @see ExecutionOptions#isBackground()
         */
        public Builder background(boolean background) {
            this.background = background;
            return this;
        }

        /**
         * Builds the execution options.
         *
         * @return the configured execution options
         * @throws NullPointerException
         *             if charset is null
         */
        public ExecutionOptions build() {
            return new ExecutionOptions(this);
        }
    }
}
