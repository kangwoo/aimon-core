/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.time.Duration;
import java.util.Objects;

/**
 * Represents a single step in a routine.
 *
 * <p>
 * Each step defines a tool to execute with its parameters, retry configuration, and timeout settings.
 * </p>
 */
public final class RoutineStep {

    /**
     * Default maximum retry attempts.
     */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * Default delay between retries.
     */
    public static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(5);

    /**
     * Default execution timeout.
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final String id;
    private final String tool;
    private final String toolParams;
    private final int maxRetries;
    private final Duration retryDelay;
    private final Duration timeout;

    private RoutineStep(Builder builder) {
        id = builder.id;
        tool = Objects.requireNonNull(builder.tool, "Tool name cannot be null");
        toolParams = builder.toolParams != null ? builder.toolParams : "";
        maxRetries = builder.maxRetries;
        retryDelay = builder.retryDelay;
        timeout = builder.timeout;
    }

    /**
     * Creates a simple routine step with default retry settings.
     *
     * @param tool
     *            the tool name
     * @param toolParams
     *            the tool parameters as a JSON string
     * @return a new routine step
     */
    public static RoutineStep of(String tool, String toolParams) {
        return builder().tool(tool).toolParams(toolParams).build();
    }

    /**
     * Creates a routine step with custom retry configuration.
     *
     * @param tool
     *            the tool name
     * @param toolParams
     *            the tool parameters as a JSON string
     * @param maxRetries
     *            maximum retry attempts
     * @param retryDelay
     *            delay between retries
     * @return a new routine step
     */
    public static RoutineStep withRetry(String tool, String toolParams, int maxRetries, Duration retryDelay) {
        return builder().tool(tool).toolParams(toolParams).maxRetries(maxRetries).retryDelay(retryDelay).build();
    }

    /** Builder를 생성한다. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the optional step id for tracing/logging.
     *
     * @return the step id, or null if not set
     */
    public String getId() {
        return id;
    }

    /** 도구 이름을 반환한다. */
    public String getTool() {
        return tool;
    }

    /** 도구 파라미터를 반환한다. */
    public String getToolParams() {
        return toolParams;
    }

    /** 최대 재시도 횟수를 반환한다. */
    public int getMaxRetries() {
        return maxRetries;
    }

    /** 재시도 간격을 반환한다. */
    public Duration getRetryDelay() {
        return retryDelay;
    }

    /** 실행 타임아웃을 반환한다. */
    public Duration getTimeout() {
        return timeout;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RoutineStep that = (RoutineStep) o;
        return maxRetries == that.maxRetries && Objects.equals(id, that.id) && tool.equals(that.tool)
                && toolParams.equals(that.toolParams) && retryDelay.equals(that.retryDelay)
                && timeout.equals(that.timeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, tool, toolParams, maxRetries, retryDelay, timeout);
    }

    @Override
    public String toString() {
        return "RoutineStep{id='" + id + "', tool='" + tool + "', toolParams=" + toolParams + ", maxRetries="
                + maxRetries + ", retryDelay=" + retryDelay + ", timeout=" + timeout + '}';
    }

    /**
     * Builder for {@link RoutineStep}.
     */
    public static final class Builder {

        private String id;
        private String tool;
        private String toolParams;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private Duration retryDelay = DEFAULT_RETRY_DELAY;
        private Duration timeout = DEFAULT_TIMEOUT;

        private Builder() {
        }

        /** 단계 ID를 설정한다. */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /** 도구 이름을 설정한다. */
        public Builder tool(String tool) {
            this.tool = Objects.requireNonNull(tool, "Tool name cannot be null");
            return this;
        }

        /** 도구 파라미터를 설정한다. */
        public Builder toolParams(String toolParams) {
            this.toolParams = toolParams;
            return this;
        }

        /** 최대 재시도 횟수를 설정한다. */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("Max retries cannot be negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /** 재시도 간격을 설정한다. */
        public Builder retryDelay(Duration retryDelay) {
            this.retryDelay = Objects.requireNonNull(retryDelay, "Retry delay cannot be null");
            return this;
        }

        /** 실행 타임아웃을 설정한다. */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "Timeout cannot be null");
            return this;
        }

        /**
         * Sets the timeout from milliseconds.
         *
         * @param timeoutMs
         *            timeout in milliseconds
         * @return this builder
         */
        public Builder timeoutMs(long timeoutMs) {
            if (timeoutMs < 0) {
                throw new IllegalArgumentException("Timeout cannot be negative");
            }
            this.timeout = Duration.ofMillis(timeoutMs);
            return this;
        }

        /** RoutineStep을 생성한다. */
        public RoutineStep build() {
            return new RoutineStep(this);
        }
    }
}
