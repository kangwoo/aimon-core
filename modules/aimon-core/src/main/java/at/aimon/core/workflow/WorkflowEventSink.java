package at.aimon.core.workflow;

/**
 * Receives progress signals from an workflow run: phase changes, log lines, and per-agent start/completion.
 *
 * <p>
 * Implementations may be invoked concurrently from worker threads (an agent inside a {@code parallel} thunk completes
 * on
 * a pool worker), so a stateful sink must be thread-safe. The default {@link #NO_OP} discards everything.
 */
public interface WorkflowEventSink {

    /** A sink that discards every signal. */
    WorkflowEventSink NO_OP = new WorkflowEventSink() {
        @Override
        public void onPhase(String title) {
            // no-op
        }

        @Override
        public void onLog(String message) {
            // no-op
        }

        @Override
        public void onAgentStarted(AgentTask task) {
            // no-op
        }

        @Override
        public void onAgentCompleted(AgentTask task, AgentStepResult result) {
            // no-op
        }
    };

    /**
     * Called when a run enters a new progress phase.
     *
     * @param title
     *            the phase title
     */
    void onPhase(String title);

    /**
     * Called for a free-form progress message.
     *
     * @param message
     *            the message
     */
    void onLog(String message);

    /**
     * Called just before a subagent begins executing.
     *
     * @param task
     *            the task about to run
     */
    void onAgentStarted(AgentTask task);

    /**
     * Called when a subagent finishes executing.
     *
     * @param task
     *            the task that ran
     * @param result
     *            its result
     */
    void onAgentCompleted(AgentTask task, AgentStepResult result);
}
