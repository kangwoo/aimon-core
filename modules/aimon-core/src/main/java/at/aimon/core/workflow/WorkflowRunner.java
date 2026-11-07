package at.aimon.core.workflow;

/**
 * Runs {@link WorkflowScript}s, foreground or in the background.
 *
 * <p>
 * <b>Lifetime is owner-determined and never application-scoped — whoever creates a runner closes it.</b> Two owners
 * exist today: the runner built by {@code OrcaAgentRuntimeFactory} when {@code workflowRunnerEnabled} is set is
 * <em>agent-scoped</em> and is closed by {@code OrcaAgentRuntime.close()}; the runners created per invocation by
 * {@code WorkflowTool} and {@code GraalJsWorkflowTool} are <em>call-scoped</em> and closed by their own
 * try-with-resources. Do not close a runner from the application shell, and do not skip closing one on the assumption
 * that some other tier owns it.
 *
 * <p>
 * The runner borrows its collaborators (a {@code SubagentExecutionManager} and a base
 * {@code SubagentLaunchContext} referencing agent-scoped resources) and <b>must not close them</b> — they
 * outlive the runner. Foreground {@link #run(WorkflowScript, RunId)} and background
 * {@link #runInBackground(WorkflowScript, RunId)} both fan out onto a single runner-owned shared pool; a background
 * run's script body is additionally hosted on a runner-owned hosting pool. {@link #close()} shuts the runner-owned
 * pools down (never the borrowed collaborators); after close, foreground {@code run()} stays usable with sequential
 * fan-out.
 */
public interface WorkflowRunner extends AutoCloseable, WorkflowRunController {

    /**
     * Default run id for the no-arg {@link #run(WorkflowScript)} — an ephemeral run with no resume: a run under this id
     * never reads or writes the {@code StepResultCache} (it is shared by every no-arg caller of every script, so
     * caching under it would let unrelated runs replay each other's outcomes).
     */
    RunId DEFAULT_RUN_ID = RunId.from("run");

    /**
     * Runs the given script to completion (synchronous, foreground) under the shared ephemeral {@link #DEFAULT_RUN_ID},
     * and returns its result. Runs under the default id never participate in step caching — use
     * {@link #run(WorkflowScript, RunId)} with a stable id to enable resume across runs.
     *
     * @param script
     *            the script to run (must not be null)
     * @param <T>
     *            the script's result type
     * @return the script's result
     */
    default <T> T run(WorkflowScript<T> script) {
        return run(script, DEFAULT_RUN_ID);
    }

    /**
     * Runs the given script to completion (synchronous, foreground) under {@code runId} and returns its result.
     *
     * <p>
     * The {@code runId} scopes the run's {@code StepResultCache} keys: re-running the same script under the same id
     * replays completed steps from the cache instead of re-executing them (design §5.3). With the default
     * {@code NO_OP} cache the id is inert.
     *
     * @param script
     *            the script to run (must not be null)
     * @param runId
     *            the run identifier (must not be null)
     * @param <T>
     *            the script's result type
     * @return the script's result
     */
    <T> T run(WorkflowScript<T> script, RunId runId);

    /**
     * Submits the script to run in the background under {@code runId} and returns a {@link RunHandle} immediately.
     *
     * <p>
     * The run is hosted on a runner-owned pool; its state is tracked in the {@code RunStore} (observable cross-node via
     * {@link #status}/{@link #list}), and it can be cancelled via {@link #stop}. Re-submitting a {@code runId} whose
     * prior run is still non-terminal is idempotent — the existing handle is returned rather than dispatching a
     * duplicate. The joined handle is cast to the caller's {@code T}: re-submitting a live id with a script of a
     * <em>different</em> result type is a caller error that surfaces as a {@code ClassCastException} where the result
     * is consumed. The typed result is owning-node only (see {@link RunHandle}).
     *
     * <p>
     * A background run takes nothing from the call that submitted it: its subagents dispatch against the registry of
     * the runner's base launch context. The hooks of a skill the submitting call runs inside therefore do not follow
     * the run. A tool that submits on behalf of a tool call must check
     * {@code HookRegistryAccess.activeSkillGuards(toolContext)} first and refuse when it is not empty, as
     * {@code Workflow} and {@code WorkflowJs} do — otherwise those guards are off for the run without any signal.
     *
     * @param script
     *            the script to run (must not be null)
     * @param runId
     *            the run identifier (must not be null)
     * @param <T>
     *            the script's result type
     * @return a handle to the background run
     */
    <T> RunHandle<T> runInBackground(WorkflowScript<T> script, RunId runId);

    /**
     * {@link #runInBackground(WorkflowScript, RunId)}, with what the run takes from the call that submitted it: the
     * submitting execution's usage quota and its tool ceiling.
     *
     * <p>
     * Everything else stays the base launch context's, as above. But a run that left the quota behind would be a way
     * around it — a user whose session is out of quota could start the same work in the background and have it run
     * unmetered — and a run that left the ceiling behind would let a skill or a fork escape its own: the base launch
     * context carries the agent's allow-list and programmatic settings, not the submitter's (register TC-25). The
     * runner therefore narrows its base by the submission: the allow-list becomes the intersection of the two, the
     * programmatic settings the submitter's narrowed by the base's. When the two allow-lists share no tool the
     * submission is refused with {@link at.aimon.core.workflow.exception.BackgroundRunRefusedException}, before any run
     * is claimed.
     *
     * <p>
     * The default ignores {@code submission} and delegates, which is right only for a runner that starts no subagent.
     * {@code DefaultWorkflowRunner} overrides it. Joining a run already in flight under {@code runId} keeps that run's
     * guard and ceiling; a caller that must not join a run started under another ceiling puts
     * {@link BackgroundSubmission#ceilingDigest()} into {@code runId}, as {@code Workflow} and {@code WorkflowJs} do.
     *
     * @param script
     *            the script to run (must not be null)
     * @param runId
     *            the run identifier (must not be null)
     * @param submission
     *            what the run takes from its submitter (must not be null; {@link BackgroundSubmission#none()} for
     *            nothing)
     * @param <T>
     *            the script's result type
     * @return a handle to the background run
     * @throws at.aimon.core.workflow.exception.BackgroundRunRefusedException
     *             if the submitter's allow-list and the base's share no tool
     */
    default <T> RunHandle<T> runInBackground(WorkflowScript<T> script, RunId runId, BackgroundSubmission submission) {
        return runInBackground(script, runId);
    }

    /**
     * Releases only resources the runner itself owns — the run-hosting pool. Borrowed collaborators (manager, base
     * launch context) are never closed here.
     */
    @Override
    void close();
}
