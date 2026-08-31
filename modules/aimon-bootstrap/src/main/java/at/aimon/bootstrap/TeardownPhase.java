package at.aimon.bootstrap;

/**
 * The total order in which an {@link AimonStack} tears its collaborators down.
 *
 * <p>
 * This enum <b>is</b> the shutdown contract. {@link TeardownRegistry#closeAll()} walks the constants in
 * declaration order, so adding a constant in the wrong place silently changes shutdown semantics for every
 * consumer. Declaration order is therefore load-bearing and each constant documents <i>why it sits after its
 * predecessor</i> — a phase whose javadoc cannot answer that question is in the wrong place.
 *
 * <h2>Why a hand-written total order exists at all</h2>
 *
 * <p>
 * Most adjacent pairs below have <b>no dependency edge</b> between them: the reason
 * {@link #REWAKE} precedes {@link #PENDING_TURNS} is not that one references the other, it is that a rewake
 * firing after the pending-turn registry is gone would resurrect a turn nobody can approve. A container that
 * derives destruction order by reversing its injection graph cannot reproduce an order whose justification is
 * not in the graph. That is why this order lives in a framework-neutral module and not in a
 * {@code @Bean(destroyMethod=...)} annotation.
 *
 * <h2>Provenance</h2>
 *
 * <p>
 * The sequence is the CLI's 14-step {@code AgentSetup#close()} order, with one substitution and three
 * additions required by a multi-session deployment:
 *
 * <ul>
 * <li><b>substitution</b> — {@link #SESSIONS} drains a whole {@code SessionRouter} rather than closing the
 * CLI's single {@code LiveSession}.
 * <li><b>additions</b> — {@link #SESSION_TRANSPORT} (distributed signal bus / inbox), {@link #AGENT_RESOURCES}
 * (per-runtime file systems and closeable tools), both of which the single-process CLI never owned.
 * </ul>
 *
 * <p>
 * Phases with no registered entry are skipped, so a stack that never wires peer memory simply passes through
 * the first four constants.
 */
public enum TeardownPhase {

    /**
     * Runs the final peer-memory derivation for the closing sessions.
     *
     * <p>
     * First because it is the only phase that <i>produces</i> work for later phases: it enqueues derivation
     * tasks that {@link #MEMORY_QUEUE} must still drain. Running it after the queue stopped would silently
     * discard the last session's observations.
     */
    MEMORY_FINAL_DERIVATION,

    /**
     * Drains and stops the memory derivation queue.
     *
     * <p>
     * After {@link #MEMORY_FINAL_DERIVATION} so the work that phase enqueued is drained rather than dropped.
     * Before {@link #DREAMER} because the dreamer writes representations the queue may still be consuming.
     */
    MEMORY_QUEUE,

    /**
     * Stops the dreamer subsystem (background consolidation walks).
     *
     * <p>
     * After {@link #MEMORY_QUEUE} so no derivation task is mid-flight against a store the dreamer is also
     * mutating; before {@link #MEMORY_MAINTENANCE} so retention purge never races an in-progress walk.
     */
    DREAMER,

    /**
     * Stops the file-backend maintenance scheduler (retention purge + append-log compaction).
     *
     * <p>
     * Last of the memory phases: compaction rewrites the very files the three phases above read, so it must
     * see a quiesced store. Before {@link #SESSIONS} only because nothing in the session path touches it —
     * the pair is genuinely independent and the order is arbitrary but fixed.
     */
    MEMORY_MAINTENANCE,

    /**
     * Gracefully drains the {@code SessionRouter}: stops accepting submits, lets in-flight turns finish
     * within the configured drain timeout, then releases every held session lease.
     *
     * <p>
     * This is the <b>substitution</b> against the CLI order, which closed a single {@code LiveSession} here.
     *
     * <p>
     * It must precede {@link #CHECKPOINTS} because closing a session performs the final end-of-turn save
     * through the checkpoint mailbox — draining the mailbox first would leave that save with nowhere to go.
     * It must precede {@link #AGENT_RUNTIMES} because a turn still draining holds tools owned by the runtime.
     */
    SESSIONS,

    /**
     * Drains and closes the {@code SessionCheckpointMailbox}.
     *
     * <p>
     * Immediately after {@link #SESSIONS} so the end-of-turn saves emitted by session close are written
     * before the mailbox's writer thread exits. Everything after this point may no longer persist transcript
     * state.
     */
    CHECKPOINTS,

    /**
     * Closes the distributed session transport — signal bus, inbox, idempotency store — in deployments that
     * replaced the single-node in-memory defaults.
     *
     * <p>
     * An <b>addition</b> against the CLI order, which had no transport to close.
     *
     * <p>
     * After {@link #CHECKPOINTS}: releasing a session lease and broadcasting the release are the last things
     * {@link #SESSIONS} does, and both travel over this transport. Closing it earlier would strand peer nodes
     * believing this node still holds sessions until the lease TTL expires.
     */
    SESSION_TRANSPORT,

    /**
     * Closes every agent runtime — which closes the MCP client manager and the agent-scoped workflow runner
     * it owns.
     *
     * <p>
     * After {@link #SESSIONS} because a runtime's tool registry is what a draining turn executes against.
     * Note this phase is <b>unbounded in wall-clock</b>: each MCP client shuts its transport down with no
     * timeout of its own.
     */
    AGENT_RUNTIMES,

    /**
     * Closes resources that belong to a runtime but that the runtime does not close itself: the per-runtime
     * {@code VirtualFileSystem} and any {@code AutoCloseable} tool registered into it.
     *
     * <p>
     * An <b>addition</b> against the CLI order, where a single process-wide file system outlived everything
     * and was never closed.
     *
     * <p>
     * Deliberately adjacent to — and after — {@link #AGENT_RUNTIMES}: closing an owner and then its
     * possessions keeps the pairing obvious, and a tool cannot be closed while the registry that dispatches
     * to it is still live.
     */
    AGENT_RESOURCES,

    /**
     * Closes shared script engines (the GraalJS engine holder and its watchdog schedulers).
     *
     * <p>
     * After {@link #AGENT_RUNTIMES} so no in-flight workflow script touches a half-closed engine — the tool
     * that resolves scripts is agent-scoped and stays alive until its runtime is torn down.
     */
    SCRIPT_ENGINES,

    /**
     * Unregisters every runtime from the {@code AgentRuntimeRegistry}.
     *
     * <p>
     * After {@link #AGENT_RUNTIMES} — the pair looks reversible but is not. Unregistering first would make
     * the registry-backed lookups used during runtime close (wiki storage locator, rewake fire listener)
     * resolve to nothing, turning a clean close into a cascade of empty {@code Optional}s.
     *
     * <p>
     * Before {@link #SCHEDULING} so a cron re-fire that lands during shutdown fails to resolve its bound
     * runtime and aborts, rather than reviving a runtime that was just closed.
     */
    RUNTIME_REGISTRY,

    /**
     * Closes the scheduling engine, its task manager and its routine executor.
     *
     * <p>
     * After {@link #RUNTIME_REGISTRY} for the reason stated there. Also a <b>long</b> phase: the underlying
     * scheduler waits for running jobs to complete.
     */
    SCHEDULING,

    /**
     * Closes the rewake service and its fire listener.
     *
     * <p>
     * After {@link #SCHEDULING} because scheduled routines are one of the things that arm rewakes; closing
     * the service while the scheduler still runs would let a routine arm a rewake into a closed service.
     * Before {@link #PENDING_TURNS} because a rewake that fires afterwards would resurrect a turn whose
     * approval registry no longer exists.
     */
    REWAKE,

    /**
     * Stops the pending-turn reaper and clears the pending-turn registry.
     *
     * <p>
     * After {@link #REWAKE} for the reason stated there. Before {@link #HOOK_HOT_RELOAD} only for
     * determinism — the two are independent.
     */
    PENDING_TURNS,

    /**
     * Stops the hook hot-reload watcher.
     *
     * <p>
     * Late by design: a reload that lands mid-shutdown would mutate a hook registry belonging to an
     * already-closed runtime. Everything that could be reloaded into is gone by this point, so the watcher's
     * remaining job is only to stop its polling thread.
     */
    HOOK_HOT_RELOAD,

    /**
     * Shuts down the thread pool that hook bodies run on.
     *
     * <p>
     * After {@link #HOOK_HOT_RELOAD} because that phase stops the last thing that can still put a hook where it
     * would be found. Everything that <i>fires</i> a hook is already gone by then — turns with
     * {@link #SESSIONS}, registries with {@link #AGENT_RUNTIMES} — so this phase only retires the workers.
     *
     * <p>
     * Before {@link #SKILL_HOOK_SHELL} for the direction this enum uses throughout: a declarative shell hook runs
     * <i>on this pool</i> and calls into that shell, so the caller stops before the callee. The reverse order
     * would leave a live pool able to invoke a closed shell.
     *
     * <p>
     * The pool's threads are daemons and it retires idle workers by itself, so a stack that never reaches this
     * phase does not hang the JVM — what the phase buys is that a host which rebuilds the stack ends the old
     * pool instead of overlapping it.
     */
    HOOK_EXECUTOR,

    /**
     * Closes the shell backing declarative skill hook actions.
     *
     * <p>
     * Last because it is the deepest leaf: skill hooks fire from hook registries (closed with their runtimes
     * in {@link #AGENT_RUNTIMES}) and from hot-reloaded declarations ({@link #HOOK_HOT_RELOAD}). Once both
     * are gone nothing can invoke a shell action, so this is the earliest point at which closing the shell
     * cannot break a caller.
     */
    SKILL_HOOK_SHELL
}
