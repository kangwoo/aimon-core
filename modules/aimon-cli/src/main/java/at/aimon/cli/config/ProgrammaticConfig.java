package at.aimon.cli.config;

import java.time.Duration;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

import at.aimon.core.programmatic.CodeExecutionLimits;

/**
 * CLI-side configuration of programmatic tool calling ({@code programmatic.*} in {@code aimon.yaml};
 * {@code docs/design/tool/programmatic-tool-calling.md} §16): whether the GraalJS engine of {@code ExecuteCode} is
 * assembled, and the limits every program is held to. What an agent opens to code is the agent's own frontmatter
 * ({@code programmatic-tools}), not this.
 *
 * <p>
 * camelCase like the rest of the CLI configuration ({@code enableWorkflowJs}). Setters exist for Jackson.
 */
public class ProgrammaticConfig {

    @JsonProperty("enableJs")
    private boolean enableJs;

    @JsonProperty("limits")
    private Limits limits;

    /** Creates an empty configuration: the engine off, the default limits. */
    public ProgrammaticConfig() {
    }

    /**
     * @return whether the GraalJS {@code ExecuteCode} engine is assembled
     */
    public boolean isEnableJs() {
        return enableJs;
    }

    /**
     * @param enableJs
     *            whether the GraalJS {@code ExecuteCode} engine is assembled
     */
    public void setEnableJs(boolean enableJs) {
        this.enableJs = enableJs;
    }

    /**
     * @return the configured limits, or null for the defaults
     */
    public Limits getLimits() {
        return limits;
    }

    /**
     * @param limits
     *            the limits, or null for the defaults
     */
    public void setLimits(Limits limits) {
        this.limits = limits;
    }

    /**
     * Returns the limits as the core value, each unset key at its default.
     *
     * @return the limits (never null)
     * @throws IllegalArgumentException
     *             if a value is not positive, or the wall clock exceeds its maximum
     */
    public CodeExecutionLimits toLimits() {
        return limits == null ? CodeExecutionLimits.defaults() : limits.toLimits();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ProgrammaticConfig other && enableJs == other.enableJs
                && Objects.equals(limits, other.limits));
    }

    @Override
    public int hashCode() {
        return Objects.hash(enableJs, limits);
    }

    @Override
    public String toString() {
        return "ProgrammaticConfig{enableJs=" + enableJs + ", limits=" + limits + '}';
    }

    /** {@code programmatic.limits.*}: every key optional, each at its {@link CodeExecutionLimits} default. */
    public static class Limits {

        @JsonProperty("wallClockSeconds")
        private Integer wallClockSeconds;

        @JsonProperty("maxWallClockSeconds")
        private Integer maxWallClockSeconds;

        @JsonProperty("maxNestedCalls")
        private Integer maxNestedCalls;

        @JsonProperty("maxMutatingCallsPerTool")
        private Integer maxMutatingCallsPerTool;

        @JsonProperty("maxConcurrentCalls")
        private Integer maxConcurrentCalls;

        @JsonProperty("maxStatements")
        private Long maxStatements;

        @JsonProperty("maxArgumentBytes")
        private Integer maxArgumentBytes;

        @JsonProperty("maxResultBytes")
        private Long maxResultBytes;

        @JsonProperty("maxScriptOutputBytes")
        private Long maxScriptOutputBytes;

        @JsonProperty("maxObservationChars")
        private Integer maxObservationChars;

        @JsonProperty("maxLogChars")
        private Integer maxLogChars;

        /** Creates limits with every key unset. */
        public Limits() {
        }

        /**
         * @return wallClockSeconds, or null for the default
         */
        public Integer getWallClockSeconds() {
            return wallClockSeconds;
        }

        /**
         * @param wallClockSeconds
         *            the value, or null for the default
         */
        public void setWallClockSeconds(Integer wallClockSeconds) {
            this.wallClockSeconds = wallClockSeconds;
        }

        /**
         * @return maxWallClockSeconds, or null for the default
         */
        public Integer getMaxWallClockSeconds() {
            return maxWallClockSeconds;
        }

        /**
         * @param maxWallClockSeconds
         *            the value, or null for the default
         */
        public void setMaxWallClockSeconds(Integer maxWallClockSeconds) {
            this.maxWallClockSeconds = maxWallClockSeconds;
        }

        /**
         * @return maxNestedCalls, or null for the default
         */
        public Integer getMaxNestedCalls() {
            return maxNestedCalls;
        }

        /**
         * @param maxNestedCalls
         *            the value, or null for the default
         */
        public void setMaxNestedCalls(Integer maxNestedCalls) {
            this.maxNestedCalls = maxNestedCalls;
        }

        /**
         * @return maxMutatingCallsPerTool, or null for the default
         */
        public Integer getMaxMutatingCallsPerTool() {
            return maxMutatingCallsPerTool;
        }

        /**
         * @param maxMutatingCallsPerTool
         *            the value, or null for the default
         */
        public void setMaxMutatingCallsPerTool(Integer maxMutatingCallsPerTool) {
            this.maxMutatingCallsPerTool = maxMutatingCallsPerTool;
        }

        /**
         * @return maxConcurrentCalls, or null for the default
         */
        public Integer getMaxConcurrentCalls() {
            return maxConcurrentCalls;
        }

        /**
         * @param maxConcurrentCalls
         *            the value, or null for the default
         */
        public void setMaxConcurrentCalls(Integer maxConcurrentCalls) {
            this.maxConcurrentCalls = maxConcurrentCalls;
        }

        /**
         * @return maxStatements, or null for the default
         */
        public Long getMaxStatements() {
            return maxStatements;
        }

        /**
         * @param maxStatements
         *            the value, or null for the default
         */
        public void setMaxStatements(Long maxStatements) {
            this.maxStatements = maxStatements;
        }

        /**
         * @return maxArgumentBytes, or null for the default
         */
        public Integer getMaxArgumentBytes() {
            return maxArgumentBytes;
        }

        /**
         * @param maxArgumentBytes
         *            the value, or null for the default
         */
        public void setMaxArgumentBytes(Integer maxArgumentBytes) {
            this.maxArgumentBytes = maxArgumentBytes;
        }

        /**
         * @return maxResultBytes, or null for the default
         */
        public Long getMaxResultBytes() {
            return maxResultBytes;
        }

        /**
         * @param maxResultBytes
         *            the value, or null for the default
         */
        public void setMaxResultBytes(Long maxResultBytes) {
            this.maxResultBytes = maxResultBytes;
        }

        /**
         * @return maxScriptOutputBytes, or null for the default
         */
        public Long getMaxScriptOutputBytes() {
            return maxScriptOutputBytes;
        }

        /**
         * @param maxScriptOutputBytes
         *            the value, or null for the default
         */
        public void setMaxScriptOutputBytes(Long maxScriptOutputBytes) {
            this.maxScriptOutputBytes = maxScriptOutputBytes;
        }

        /**
         * @return maxObservationChars, or null for the default
         */
        public Integer getMaxObservationChars() {
            return maxObservationChars;
        }

        /**
         * @param maxObservationChars
         *            the value, or null for the default
         */
        public void setMaxObservationChars(Integer maxObservationChars) {
            this.maxObservationChars = maxObservationChars;
        }

        /**
         * @return maxLogChars, or null for the default
         */
        public Integer getMaxLogChars() {
            return maxLogChars;
        }

        /**
         * @param maxLogChars
         *            the value, or null for the default
         */
        public void setMaxLogChars(Integer maxLogChars) {
            this.maxLogChars = maxLogChars;
        }

        CodeExecutionLimits toLimits() {
            final CodeExecutionLimits.Builder builder = CodeExecutionLimits.builder();
            if (wallClockSeconds != null) {
                builder.wallClock(Duration.ofSeconds(wallClockSeconds));
            }
            if (maxWallClockSeconds != null) {
                builder.maxWallClock(Duration.ofSeconds(maxWallClockSeconds));
            }
            if (maxNestedCalls != null) {
                builder.maxNestedCalls(maxNestedCalls);
            }
            if (maxMutatingCallsPerTool != null) {
                builder.maxMutatingCallsPerTool(maxMutatingCallsPerTool);
            }
            if (maxConcurrentCalls != null) {
                builder.maxConcurrentCalls(maxConcurrentCalls);
            }
            if (maxStatements != null) {
                builder.maxStatements(maxStatements);
            }
            if (maxArgumentBytes != null) {
                builder.maxArgumentBytes(maxArgumentBytes);
            }
            if (maxResultBytes != null) {
                builder.maxResultBytes(maxResultBytes);
            }
            if (maxScriptOutputBytes != null) {
                builder.maxScriptOutputBytes(maxScriptOutputBytes);
            }
            if (maxObservationChars != null) {
                builder.maxObservationChars(maxObservationChars);
            }
            if (maxLogChars != null) {
                builder.maxLogChars(maxLogChars);
            }
            return builder.build();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Limits other)) {
                return false;
            }
            return Objects.equals(wallClockSeconds, other.wallClockSeconds)
                    && Objects.equals(maxWallClockSeconds, other.maxWallClockSeconds)
                    && Objects.equals(maxNestedCalls, other.maxNestedCalls)
                    && Objects.equals(maxMutatingCallsPerTool, other.maxMutatingCallsPerTool)
                    && Objects.equals(maxConcurrentCalls, other.maxConcurrentCalls)
                    && Objects.equals(maxStatements, other.maxStatements)
                    && Objects.equals(maxArgumentBytes, other.maxArgumentBytes)
                    && Objects.equals(maxResultBytes, other.maxResultBytes)
                    && Objects.equals(maxScriptOutputBytes, other.maxScriptOutputBytes)
                    && Objects.equals(maxObservationChars, other.maxObservationChars)
                    && Objects.equals(maxLogChars, other.maxLogChars);
        }

        @Override
        public int hashCode() {
            return Objects.hash(wallClockSeconds, maxWallClockSeconds, maxNestedCalls, maxMutatingCallsPerTool,
                    maxConcurrentCalls, maxStatements, maxArgumentBytes, maxResultBytes, maxScriptOutputBytes,
                    maxObservationChars, maxLogChars);
        }

        @Override
        public String toString() {
            return "Limits{" + "wallClockSeconds=" + wallClockSeconds + ", " + "maxWallClockSeconds="
                    + maxWallClockSeconds + ", " + "maxNestedCalls=" + maxNestedCalls + ", "
                    + "maxMutatingCallsPerTool=" + maxMutatingCallsPerTool + ", " + "maxConcurrentCalls="
                    + maxConcurrentCalls + ", " + "maxStatements=" + maxStatements + ", " + "maxArgumentBytes="
                    + maxArgumentBytes + ", " + "maxResultBytes=" + maxResultBytes + ", " + "maxScriptOutputBytes="
                    + maxScriptOutputBytes + ", " + "maxObservationChars=" + maxObservationChars + ", " + "maxLogChars="
                    + maxLogChars + '}';
        }
    }
}
