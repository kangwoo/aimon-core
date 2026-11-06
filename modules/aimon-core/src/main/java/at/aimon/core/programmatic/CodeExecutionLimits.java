package at.aimon.core.programmatic;

import java.time.Duration;
import java.util.Objects;

/**
 * The limits one program run by {@code ExecuteCode} is held to ({@code docs/design/tool/programmatic-tool-calling.md}
 * §13.1). The size limits named in bytes are counted in characters by the GraalJS engine, whose guest and host
 * exchange strings; an engine that exchanges bytes (the shell engine's line protocol) counts bytes. An
 * application-level setting: {@code AimonStack} / starter {@code aimon.programmatic.limits.*}, CLI
 * {@code programmatic.limits.*}. Defaults are the design's provisional values.
 *
 * <p>
 * Immutable. Use {@link #builder()} or {@link #defaults()}.
 */
public final class CodeExecutionLimits {

    private static final CodeExecutionLimits DEFAULTS = builder().build();
    private final Duration wallClock;
    private final Duration maxWallClock;
    private final int maxNestedCalls;
    private final int maxMutatingCallsPerTool;
    private final int maxConcurrentCalls;
    private final long maxStatements;
    private final int maxArgumentBytes;
    private final long maxResultBytes;
    private final long maxScriptOutputBytes;
    private final int maxObservationChars;
    private final int maxLogChars;

    private CodeExecutionLimits(Builder builder) {
        this.wallClock = builder.wallClock;
        this.maxWallClock = builder.maxWallClock;
        this.maxNestedCalls = builder.maxNestedCalls;
        this.maxMutatingCallsPerTool = builder.maxMutatingCallsPerTool;
        this.maxConcurrentCalls = builder.maxConcurrentCalls;
        this.maxStatements = builder.maxStatements;
        this.maxArgumentBytes = builder.maxArgumentBytes;
        this.maxResultBytes = builder.maxResultBytes;
        this.maxScriptOutputBytes = builder.maxScriptOutputBytes;
        this.maxObservationChars = builder.maxObservationChars;
        this.maxLogChars = builder.maxLogChars;
        if (wallClock.compareTo(maxWallClock) > 0) {
            throw new IllegalArgumentException("wallClock " + wallClock + " exceeds maxWallClock " + maxWallClock);
        }
    }

    /**
     * Returns the design's default limits.
     *
     * @return the defaults (never null)
     */
    public static CodeExecutionLimits defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder pre-filled with the defaults.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the wall clock a program gets when it asks for {@code requestedSeconds} (or nothing): the request capped
     * at {@link #getMaxWallClock()}, or {@link #getWallClock()} when it asks for nothing or a non-positive value.
     *
     * @param requestedSeconds
     *            the {@code timeout_seconds} input, or {@code null}
     * @return the wall clock (never null, positive)
     */
    public Duration wallClockFor(Integer requestedSeconds) {
        if (requestedSeconds == null || requestedSeconds <= 0) {
            return wallClock;
        }
        final Duration requested = Duration.ofSeconds(requestedSeconds);
        return requested.compareTo(maxWallClock) > 0 ? maxWallClock : requested;
    }

    /**
     * Returns the default wall-clock limit of one program (120 s).
     *
     * @return the limit
     */
    public Duration getWallClock() {
        return wallClock;
    }

    /**
     * Returns the most a program may ask for with {@code timeout_seconds} (600 s).
     *
     * @return the limit
     */
    public Duration getMaxWallClock() {
        return maxWallClock;
    }

    /**
     * Returns the most tool calls one program may make (200); one more stops the program with {@code LIMIT_EXCEEDED}.
     *
     * @return the limit
     */
    public int getMaxNestedCalls() {
        return maxNestedCalls;
    }

    /**
     * Returns the most calls one program may make to one {@code MUTATING} tool (20); later calls to it are refused.
     *
     * @return the limit
     */
    public int getMaxMutatingCallsPerTool() {
        return maxMutatingCallsPerTool;
    }

    /**
     * Returns the most nested calls running at once (8); more wait in the bridge's queue.
     *
     * @return the limit
     */
    public int getMaxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    /**
     * Returns the JavaScript statement limit (10M).
     *
     * @return the limit
     */
    public long getMaxStatements() {
        return maxStatements;
    }

    /**
     * Returns the largest arguments of one nested call, in bytes (1 MiB); larger ones are refused.
     *
     * @return the limit
     */
    public int getMaxArgumentBytes() {
        return maxArgumentBytes;
    }

    /**
     * Returns the total bytes of tool results one program may take in (16 MiB, GraalJS).
     *
     * @return the limit
     */
    public long getMaxResultBytes() {
        return maxResultBytes;
    }

    /**
     * Returns the total stdout + stderr one program may write (4 MiB); more stops it.
     *
     * @return the limit
     */
    public long getMaxScriptOutputBytes() {
        return maxScriptOutputBytes;
    }

    /**
     * Returns the longest stdout the observation keeps (20,000 chars); the rest is cut with a marker.
     *
     * @return the limit
     */
    public int getMaxObservationChars() {
        return maxObservationChars;
    }

    /**
     * Returns the longest log tail the observation keeps (4,000 chars).
     *
     * @return the limit
     */
    public int getMaxLogChars() {
        return maxLogChars;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CodeExecutionLimits other)) {
            return false;
        }
        return wallClock.equals(other.wallClock) && maxWallClock.equals(other.maxWallClock)
                && maxNestedCalls == other.maxNestedCalls && maxMutatingCallsPerTool == other.maxMutatingCallsPerTool
                && maxConcurrentCalls == other.maxConcurrentCalls && maxStatements == other.maxStatements
                && maxArgumentBytes == other.maxArgumentBytes && maxResultBytes == other.maxResultBytes
                && maxScriptOutputBytes == other.maxScriptOutputBytes
                && maxObservationChars == other.maxObservationChars && maxLogChars == other.maxLogChars;
    }

    @Override
    public int hashCode() {
        return Objects.hash(wallClock, maxWallClock, maxNestedCalls, maxMutatingCallsPerTool, maxConcurrentCalls,
                maxStatements, maxArgumentBytes, maxResultBytes, maxScriptOutputBytes, maxObservationChars,
                maxLogChars);
    }

    @Override
    public String toString() {
        return "CodeExecutionLimits{" + "wallClock=" + wallClock + ", " + "maxWallClock=" + maxWallClock + ", "
                + "maxNestedCalls=" + maxNestedCalls + ", " + "maxMutatingCallsPerTool=" + maxMutatingCallsPerTool
                + ", " + "maxConcurrentCalls=" + maxConcurrentCalls + ", " + "maxStatements=" + maxStatements + ", "
                + "maxArgumentBytes=" + maxArgumentBytes + ", " + "maxResultBytes=" + maxResultBytes + ", "
                + "maxScriptOutputBytes=" + maxScriptOutputBytes + ", " + "maxObservationChars=" + maxObservationChars
                + ", " + "maxLogChars=" + maxLogChars + '}';
    }

    /** Builder for {@link CodeExecutionLimits}, pre-filled with the defaults. */
    public static final class Builder {
        private Duration wallClock = Duration.ofSeconds(120);
        private Duration maxWallClock = Duration.ofSeconds(600);
        private int maxNestedCalls = 200;
        private int maxMutatingCallsPerTool = 20;
        private int maxConcurrentCalls = 8;
        private long maxStatements = 10_000_000L;
        private int maxArgumentBytes = 1024 * 1024;
        private long maxResultBytes = 16L * 1024 * 1024;
        private long maxScriptOutputBytes = 4L * 1024 * 1024;
        private int maxObservationChars = 20_000;
        private int maxLogChars = 4_000;

        private Builder() {
        }

        /**
         * Sets the default wall-clock limit of one program (120 s).
         *
         * @param wallClock
         *            the limit (must be positive)
         * @return this builder
         */
        public Builder wallClock(Duration wallClock) {
            Objects.requireNonNull(wallClock, "wallClock cannot be null");
            if (wallClock.isZero() || wallClock.isNegative()) {
                throw new IllegalArgumentException("wallClock must be positive: " + wallClock);
            }
            this.wallClock = wallClock;
            return this;
        }

        /**
         * Sets the most a program may ask for with {@code timeout_seconds} (600 s).
         *
         * @param maxWallClock
         *            the limit (must be positive)
         * @return this builder
         */
        public Builder maxWallClock(Duration maxWallClock) {
            Objects.requireNonNull(maxWallClock, "maxWallClock cannot be null");
            if (maxWallClock.isZero() || maxWallClock.isNegative()) {
                throw new IllegalArgumentException("maxWallClock must be positive: " + maxWallClock);
            }
            this.maxWallClock = maxWallClock;
            return this;
        }

        /**
         * Sets the most tool calls one program may make (200); one more stops the program with {@code LIMIT_EXCEEDED}.
         *
         * @param maxNestedCalls
         *            the limit (must be positive)
         * @return this builder
         */
        public Builder maxNestedCalls(int maxNestedCalls) {
            if (maxNestedCalls < 1) {
                throw new IllegalArgumentException("maxNestedCalls must be positive: " + maxNestedCalls);
            }
            this.maxNestedCalls = maxNestedCalls;
            return this;
        }

        /**
         * Sets the most calls one program may make to one {@code MUTATING} tool (20); later calls to it are refused.
         *
         * @param maxMutatingCallsPerTool
         *            the limit (must be positive)
         * @return this builder
         */
        public Builder maxMutatingCallsPerTool(int maxMutatingCallsPerTool) {
            if (maxMutatingCallsPerTool < 1) {
                throw new IllegalArgumentException(
                        "maxMutatingCallsPerTool must be positive: " + maxMutatingCallsPerTool);
            }
            this.maxMutatingCallsPerTool = maxMutatingCallsPerTool;
            return this;
        }

        /**
         * Sets the most nested calls running at once (8); more wait in the bridge's queue.
         *
         * @param maxConcurrentCalls
         *            the limit (must be positive)
         * @return this builder
         */
        public Builder maxConcurrentCalls(int maxConcurrentCalls) {
            if (maxConcurrentCalls < 1) {
                throw new IllegalArgumentException("maxConcurrentCalls must be positive: " + maxConcurrentCalls);
            }
            this.maxConcurrentCalls = maxConcurrentCalls;
            return this;
        }

        /**
         * Sets the JavaScript statement limit (10M).
         *
         * @param maxStatements
         *            the limit (must be positive)
         * @return this builder
         */
        public Builder maxStatements(long maxStatements) {
            if (maxStatements < 1) {
                throw new IllegalArgumentException("maxStatements must be positive: " + maxStatements);
            }
            this.maxStatements = maxStatements;
            return this;
        }

        /**
         * Sets the largest arguments of one nested call, in bytes (1 MiB); larger ones are refused.
         *
         * @param maxArgumentBytes
         *            the limit (must be positive)
         * @return this builder
         */
        public Builder maxArgumentBytes(int maxArgumentBytes) {
            if (maxArgumentBytes < 1) {
                throw new IllegalArgumentException("maxArgumentBytes must be positive: " + maxArgumentBytes);
            }
            this.maxArgumentBytes = maxArgumentBytes;
            return this;
        }

        /**
         * Sets the total bytes of tool results one program may take in (16 MiB, GraalJS).
         *
         * @param maxResultBytes
         *            the limit (must be positive)
         * @return this builder
         */
        public Builder maxResultBytes(long maxResultBytes) {
            if (maxResultBytes < 1) {
                throw new IllegalArgumentException("maxResultBytes must be positive: " + maxResultBytes);
            }
            this.maxResultBytes = maxResultBytes;
            return this;
        }

        /**
         * Sets the total stdout + stderr one program may write (4 MiB); more stops it.
         *
         * @param maxScriptOutputBytes
         *            the limit (must be positive)
         * @return this builder
         */
        public Builder maxScriptOutputBytes(long maxScriptOutputBytes) {
            if (maxScriptOutputBytes < 1) {
                throw new IllegalArgumentException("maxScriptOutputBytes must be positive: " + maxScriptOutputBytes);
            }
            this.maxScriptOutputBytes = maxScriptOutputBytes;
            return this;
        }

        /**
         * Sets the longest stdout the observation keeps (20,000 chars); the rest is cut with a marker.
         *
         * @param maxObservationChars
         *            the limit (must be positive)
         * @return this builder
         */
        public Builder maxObservationChars(int maxObservationChars) {
            if (maxObservationChars < 1) {
                throw new IllegalArgumentException("maxObservationChars must be positive: " + maxObservationChars);
            }
            this.maxObservationChars = maxObservationChars;
            return this;
        }

        /**
         * Sets the longest log tail the observation keeps (4,000 chars).
         *
         * @param maxLogChars
         *            the limit (must be positive)
         * @return this builder
         */
        public Builder maxLogChars(int maxLogChars) {
            if (maxLogChars < 1) {
                throw new IllegalArgumentException("maxLogChars must be positive: " + maxLogChars);
            }
            this.maxLogChars = maxLogChars;
            return this;
        }

        /**
         * @return the limits (never null)
         * @throws IllegalArgumentException
         *             if {@code wallClock} exceeds {@code maxWallClock}
         */
        public CodeExecutionLimits build() {
            return new CodeExecutionLimits(this);
        }
    }
}
