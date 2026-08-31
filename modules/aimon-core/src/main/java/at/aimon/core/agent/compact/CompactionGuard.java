package at.aimon.core.agent.compact;

import java.util.Objects;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmModel;

/**
 * Decides at every ReAct iteration boundary whether the conversation should be compacted, warned about, or blocked.
 *
 * <p>
 * The guard owns:
 *
 * <ul>
 * <li><b>Threshold evaluation</b> against {@link at.aimon.core.llm.ModelContextLimits} resolved from the current model.
 * <li><b>Precondition checks</b> ensuring the last message is in a stable state (no pending tool_use) before invoking
 * the engine.
 * <li><b>Circuit breaker</b> per {@link at.aimon.core.agent.session.SessionId} — opens after a fixed number
 * of
 * consecutive AUTO compaction failures and is reset by a successful compaction or a successful MANUAL invocation.
 * <li><b>Per-session serialization</b> so two threads cannot enter compaction for the same session concurrently.
 * </ul>
 *
 * <p>
 * Implementations must be thread-safe. {@link DefaultCompactionGuard} provides the standard rule set; the
 * {@link NoOpCompactionGuard} preserves pre-compaction behaviour and is the framework default.
 *
 * <p>
 * <b>Which overload to implement.</b> {@link #maybeCompact(TranscriptBuffer, LlmModel, HookRegistry, Environment)} is
 * the single abstract method and remains so; the {@link ExecutionId}-carrying overloads are {@code default} methods
 * that delegate to their four-argument counterparts. The delegation runs in that direction, and not the other way
 * round, so that an implementor that only knows the four-argument contract still compiles and still behaves exactly as
 * before &mdash; at the cost that a session-less run compacted through such a guard keeps identifying itself by its
 * transcript label, which is what every guard did before the overload existed. An implementation that wants a
 * session-less run to be identified honestly overrides the five-argument overloads too, as
 * {@link DefaultCompactionGuard} does.
 */
public interface CompactionGuard {

    /**
     * Evaluates the current conversation against compaction thresholds and (optionally) performs an AUTO compaction.
     *
     * <p>
     * The guard is invoked exclusively from the AUTO path; MANUAL compactions go directly to
     * {@link CompactionEngine#compact(CompactionRequest)} bypassing the guard.
     *
     * @param memory
     *            the live transcript buffer (must not be null)
     * @param model
     *            the model used for the next ReAct call — drives threshold resolution (must not be null)
     * @param hookRegistry
     *            the registry whose PreCompact / PostCompact hooks will be invoked if compaction proceeds (must not be
     *            null)
     * @param environment
     *            the active environment (must not be null)
     * @return a {@link CompactionDecision} describing the outcome (never null)
     * @throws NullPointerException
     *             if any argument is null
     */
    CompactionDecision maybeCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
            Environment environment);

    /**
     * Same as {@link #maybeCompact(TranscriptBuffer, LlmModel, HookRegistry, Environment)}, for a run that has no
     * session of its own &mdash; a subagent fork, for instance.
     *
     * <p>
     * Call this overload whenever the caller holds an {@link ExecutionId}. Compaction fires PreCompact hooks, and a
     * hook has to be told which run it is looking at; without this parameter the only identity reaching it is
     * {@link TranscriptBuffer#getSessionId()}, which for a session-less run is a label rather than a session, and
     * exporting that label as a session id is a lie a hook cannot detect. Guards forward the id to the
     * {@link CompactionEngine} through {@link CompactionRequest.Builder#executionId(ExecutionId)}.
     *
     * @param memory
     *            the live transcript buffer (must not be null)
     * @param model
     *            the model used for the next ReAct call — drives threshold resolution (must not be null)
     * @param hookRegistry
     *            the registry whose PreCompact / PostCompact hooks will be invoked if compaction proceeds (must not be
     *            null)
     * @param environment
     *            the active environment (must not be null)
     * @param executionId
     *            the identity of the session-less run being compacted (must not be null — a run that <em>has</em> a
     *            session calls the four-argument overload instead)
     * @return a {@link CompactionDecision} describing the outcome (never null)
     * @throws NullPointerException
     *             if any argument is null
     */
    default CompactionDecision maybeCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
            Environment environment, ExecutionId executionId) {
        Objects.requireNonNull(executionId, "executionId cannot be null");
        return maybeCompact(memory, model, hookRegistry, environment);
    }

    /**
     * Proactively compacts the conversation in response to an external hint (e.g. a
     * {@link at.aimon.core.agent.budget.BudgetDecision#SHOULD_COMPACT} signal from the budget tracker), rather than
     * waiting for the model's own auto-compact threshold.
     *
     * <p>
     * Contract: same preconditions, circuit breaker, and per-session serialization as {@link #maybeCompact}; the
     * only difference is a <em>lower</em> effective trigger so compaction happens earlier. Implementations that have no
     * lower band to drop to (and the {@link NoOpCompactionGuard}) simply behave as {@link #maybeCompact} — hence the
     * default delegates, making this safe to call on any guard.
     *
     * @param memory
     *            the live transcript buffer (must not be null)
     * @param model
     *            the model used for the next ReAct call — drives threshold resolution (must not be null)
     * @param hookRegistry
     *            the registry whose PreCompact / PostCompact hooks will be invoked if compaction proceeds (must not be
     *            null)
     * @param environment
     *            the active environment (must not be null)
     * @return a {@link CompactionDecision} describing the outcome (never null)
     * @throws NullPointerException
     *             if any argument is null
     */
    default CompactionDecision forceCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
            Environment environment) {
        return maybeCompact(memory, model, hookRegistry, environment);
    }

    /**
     * Same as {@link #forceCompact(TranscriptBuffer, LlmModel, HookRegistry, Environment)}, for a run that has no
     * session of its own. See {@link #maybeCompact(TranscriptBuffer, LlmModel, HookRegistry, Environment, ExecutionId)}
     * for why the id has to be passed explicitly.
     *
     * <p>
     * The default delegates to the four-argument {@code forceCompact} rather than to the five-argument
     * {@code maybeCompact}, so a guard that overrode only the former keeps its lowered trigger band.
     *
     * @param memory
     *            the live transcript buffer (must not be null)
     * @param model
     *            the model used for the next ReAct call — drives threshold resolution (must not be null)
     * @param hookRegistry
     *            the registry whose PreCompact / PostCompact hooks will be invoked if compaction proceeds (must not be
     *            null)
     * @param environment
     *            the active environment (must not be null)
     * @param executionId
     *            the identity of the session-less run being compacted (must not be null)
     * @return a {@link CompactionDecision} describing the outcome (never null)
     * @throws NullPointerException
     *             if any argument is null
     */
    default CompactionDecision forceCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
            Environment environment, ExecutionId executionId) {
        Objects.requireNonNull(executionId, "executionId cannot be null");
        return forceCompact(memory, model, hookRegistry, environment);
    }

    /**
     * Notifies the guard that a compaction succeeded outside of the {@link #maybeCompact} path — typically a MANUAL
     * compaction issued via {@code /compact} that bypasses the guard but still wants the AUTO circuit breaker to
     * reopen. The default implementation is a no-op for guards that do not maintain failure state.
     *
     * @param sessionId
     *            the session whose failure counter should be reset (must not be null)
     */
    default void recordExternalSuccess(SessionId sessionId) {
        // no-op by default — guards that maintain a circuit breaker (e.g. DefaultCompactionGuard) override this
    }
}
