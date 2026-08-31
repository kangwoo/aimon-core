package at.aimon.bootstrap.spec;

import java.util.Optional;

import at.aimon.core.llm.cost.CostEstimator;
import at.aimon.core.memory.MemoryContextProvider;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.Tracer;

/**
 * Declares the optional capabilities of the ReAct executor: streaming, tracing, cost accounting, and the memory
 * context provider.
 *
 * <p>
 * Everything here is genuinely optional. The stack builds a working executor with none of it set, and each field
 * left empty means the corresponding subsystem is simply absent — not misconfigured. That is the reason these
 * live in a spec of their own rather than as more parameters on {@link at.aimon.bootstrap.AimonStackSpec}: they
 * are a coherent group of "what the loop does beyond calling the model", and a caller who wants none of them
 * never has to name the type.
 *
 * <p>
 * Two of the fields are a pair. A {@link Tracer} without a {@link TracePayloadPolicy} traces with the executor's
 * default payload handling; a policy without a tracer is inert, and the stack reports that as a degradation
 * rather than failing, because a payload policy bound from configuration is exactly the kind of setting that
 * outlives the tracer someone removed.
 */
public final class ExecutorSpec {

    private static final ExecutorSpec DEFAULTS = builder().build();

    private final boolean streaming;
    private final Tracer tracer;
    private final TracePayloadPolicy tracePayloadPolicy;
    private final CostEstimator costEstimator;
    private final MemoryContextProvider memoryContextProvider;

    private ExecutorSpec(Builder builder) {
        this.streaming = builder.streaming;
        this.tracer = builder.tracer;
        this.tracePayloadPolicy = builder.tracePayloadPolicy;
        this.costEstimator = builder.costEstimator;
        this.memoryContextProvider = builder.memoryContextProvider;
    }

    /**
     * Returns the default spec: no streaming, no tracing, no memory context, and the stack's default cost
     * estimator.
     *
     * @return the shared default instance
     */
    public static ExecutorSpec defaults() {
        return DEFAULTS;
    }

    /**
     * Creates a builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether the executor streams assistant output token by token.
     *
     * @return {@code true} when streaming is on
     */
    public boolean isStreaming() {
        return streaming;
    }

    /**
     * Returns the tracer.
     *
     * @return the tracer, or empty when tracing is off
     */
    public Optional<Tracer> getTracer() {
        return Optional.ofNullable(tracer);
    }

    /**
     * Returns the trace payload policy.
     *
     * @return the policy, or empty for the executor default
     */
    public Optional<TracePayloadPolicy> getTracePayloadPolicy() {
        return Optional.ofNullable(tracePayloadPolicy);
    }

    /**
     * Returns the cost estimator override.
     *
     * @return the estimator, or empty to use the stack's table-priced default
     */
    public Optional<CostEstimator> getCostEstimator() {
        return Optional.ofNullable(costEstimator);
    }

    /**
     * Returns the memory context provider.
     *
     * @return the provider, or empty when no memory subsystem is wired
     */
    public Optional<MemoryContextProvider> getMemoryContextProvider() {
        return Optional.ofNullable(memoryContextProvider);
    }

    @Override
    public String toString() {
        return "ExecutorSpec[streaming=" + streaming + ", tracer=" + (tracer != null) + ", memoryContext="
                + (memoryContextProvider != null) + "]";
    }

    /** Builder for {@link ExecutorSpec}. */
    public static final class Builder {

        private boolean streaming;
        private Tracer tracer;
        private TracePayloadPolicy tracePayloadPolicy;
        private CostEstimator costEstimator;
        private MemoryContextProvider memoryContextProvider;

        private Builder() {
        }

        /**
         * Turns token-by-token streaming on or off.
         *
         * @param streaming
         *            {@code true} to stream
         * @return this builder
         */
        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        /**
         * Sets the tracer that records spans for iterations, LLM calls and tool executions.
         *
         * @param tracer
         *            the tracer, or {@code null} for none
         * @return this builder
         */
        public Builder tracer(Tracer tracer) {
            this.tracer = tracer;
            return this;
        }

        /**
         * Sets how much of each prompt and response the tracer is allowed to record.
         *
         * @param tracePayloadPolicy
         *            the policy, or {@code null} for the executor default
         * @return this builder
         */
        public Builder tracePayloadPolicy(TracePayloadPolicy tracePayloadPolicy) {
            this.tracePayloadPolicy = tracePayloadPolicy;
            return this;
        }

        /**
         * Overrides the cost estimator.
         *
         * @param costEstimator
         *            the estimator, or {@code null} for the stack's table-priced default
         * @return this builder
         */
        public Builder costEstimator(CostEstimator costEstimator) {
            this.costEstimator = costEstimator;
            return this;
        }

        /**
         * Sets the provider that injects recalled memory into each turn's context.
         *
         * @param memoryContextProvider
         *            the provider, or {@code null} for none
         * @return this builder
         */
        public Builder memoryContextProvider(MemoryContextProvider memoryContextProvider) {
            this.memoryContextProvider = memoryContextProvider;
            return this;
        }

        /**
         * Builds the spec.
         *
         * @return the immutable spec
         */
        public ExecutorSpec build() {
            return new ExecutorSpec(this);
        }
    }
}
