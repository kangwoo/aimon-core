package at.aimon.core.agent.compact;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmModel;

/**
 * Everything one AUTO compaction pass hands a {@link CompactionGuard}, as a single value.
 *
 * <p>
 * The guard's positional entry points grew one overload per optional input ({@code maybeCompact} / {@code forceCompact}
 * × with or without an {@link ExecutionId}). The execution environment is a third optional input; rather than double
 * the overloads again, a caller that has more to pass than the positional methods can carry uses
 * {@link CompactionGuard#maybeCompact(CompactionGuardRequest)}.
 *
 * <p>
 * Immutable value object built via {@link Builder}.
 */
public final class CompactionGuardRequest {

    private final TranscriptBuffer transcriptBuffer;
    private final LlmModel model;
    private final HookRegistry hookRegistry;
    private final ExecutionId executionId;
    private final ExecutionEnvironment executionEnvironment;
    private final CancellationSignal executionCancellation;
    private final boolean budgetForced;

    private CompactionGuardRequest(Builder builder) {
        this.transcriptBuffer = Objects.requireNonNull(builder.transcriptBuffer, "transcriptBuffer cannot be null");
        this.model = Objects.requireNonNull(builder.model, "model cannot be null");
        this.hookRegistry = Objects.requireNonNull(builder.hookRegistry, "hookRegistry cannot be null");
        this.executionId = builder.executionId;
        this.executionEnvironment = builder.executionEnvironment;
        this.executionCancellation = builder.executionCancellation;
        this.budgetForced = builder.budgetForced;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The live transcript buffer. */
    public TranscriptBuffer getTranscriptBuffer() {
        return transcriptBuffer;
    }

    /** The model the next ReAct call goes to &mdash; drives threshold resolution. */
    public LlmModel getModel() {
        return model;
    }

    /** The registry whose PreCompact / PostCompact hooks fire if compaction proceeds. */
    public HookRegistry getHookRegistry() {
        return hookRegistry;
    }

    /**
     * The identity of a session-less run being compacted. Empty when the compaction belongs to a genuine session and
     * the buffer's session id is the honest identity.
     */
    public Optional<ExecutionId> getExecutionId() {
        return Optional.ofNullable(executionId);
    }

    /**
     * The execution environment of the execution being compacted, for the compaction hooks. Empty when the caller had
     * none in reach.
     */
    public Optional<ExecutionEnvironment> getExecutionEnvironment() {
        return Optional.ofNullable(executionEnvironment);
    }

    /**
     * The cancellation signal of the execution being compacted, carried into the PreCompact / PostCompact hook
     * contexts so a hook's command stops when that execution is interrupted. Empty when no signal there can trip — a
     * manual {@code /compact}, a rewake replay — or the caller had none in reach.
     *
     * @return the compacting execution's cancellation signal, or empty when there is none
     */
    public Optional<CancellationSignal> getExecutionCancellation() {
        return Optional.ofNullable(executionCancellation);
    }

    /**
     * Whether a budget hint asked for proactive compaction &mdash; the {@code forceCompact} half of the positional
     * entry points.
     */
    public boolean isBudgetForced() {
        return budgetForced;
    }

    /** Builder for {@link CompactionGuardRequest}. */
    public static final class Builder {
        private TranscriptBuffer transcriptBuffer;
        private LlmModel model;
        private HookRegistry hookRegistry;
        private ExecutionId executionId;
        private ExecutionEnvironment executionEnvironment;
        private CancellationSignal executionCancellation;
        private boolean budgetForced;

        private Builder() {
        }

        public Builder transcriptBuffer(TranscriptBuffer transcriptBuffer) {
            this.transcriptBuffer = transcriptBuffer;
            return this;
        }

        public Builder model(LlmModel model) {
            this.model = model;
            return this;
        }

        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        public Builder executionId(ExecutionId executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder executionEnvironment(ExecutionEnvironment executionEnvironment) {
            this.executionEnvironment = executionEnvironment;
            return this;
        }

        /**
         * @param executionCancellation
         *            the compacting execution's cancellation signal for the compaction hooks, or {@code null} when
         *            there is none
         * @return this builder
         */
        public Builder executionCancellation(CancellationSignal executionCancellation) {
            this.executionCancellation = executionCancellation;
            return this;
        }

        public Builder budgetForced(boolean budgetForced) {
            this.budgetForced = budgetForced;
            return this;
        }

        public CompactionGuardRequest build() {
            return new CompactionGuardRequest(this);
        }
    }
}
