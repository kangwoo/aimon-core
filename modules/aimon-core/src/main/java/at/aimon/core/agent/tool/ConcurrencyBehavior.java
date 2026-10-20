package at.aimon.core.agent.tool;

/**
 * Declares whether a {@link Tool} is safe to run concurrently with other tools dispatched in the same batch.
 *
 * <p>
 * A "batch" is the set of {@code tool_use} blocks returned in a single LLM response. When the model returns several
 * tool_uses at once it is signalling that they are independent; the framework still verifies safety through this
 * declaration before any parallel execution happens. The pairing mirrors the
 * {@link at.aimon.core.agent.interrupt.InterruptBehavior} / {@code InterruptCoordinator} split: the tool declares a
 * capability, a separate coordinator (the {@code ParallelToolDispatcher}) decides how to act on it.
 *
 * <p>
 * The default for every tool is {@link #SEQUENTIAL}, so existing tools keep their run-to-completion-in-order semantics
 * unless they explicitly opt in.
 *
 * <p>
 * The {@code Behavior} suffix places this with {@link at.aimon.core.agent.interrupt.InterruptBehavior} as an
 * <em>unordered</em> trait a tool declares about itself, as against {@link SideEffectLevel}, which is ordered and
 * compared. It was called {@code ConcurrencyPolicy} until that distinction was drawn; see {@link SideEffectLevel} for
 * the reasoning.
 *
 * @see Tool#getConcurrencyBehavior()
 * @see ParallelToolDispatcher
 */
public enum ConcurrencyBehavior {

    /**
     * The default. The tool must run sequentially and is never parallelised with sibling tools in the same batch. Use
     * for tools that mutate files or sandbox state, mutate shared in-memory state non-atomically, or carry an ordering
     * dependency on neighbouring tool_uses (e.g. {@code Edit}, {@code Write}, {@code Bash}, {@code TodoWrite}).
     */
    SEQUENTIAL,

    /**
     * The tool is safe to run at the same time as other {@code CONCURRENT_SAFE} tools in the same batch. It must be
     * free of observable side effects (or idempotent) and must only touch shared mutable state through thread-safe
     * means. Use for read-only / idempotent tools (e.g. {@code Read}, {@code Grep}, {@code WebFetch}).
     *
     * <p>
     * Declaring this is a contract: the tool's {@code execute} method, and any Pre/PostTool hooks registered for it,
     * must tolerate being invoked from a worker thread concurrently with other tools.
     */
    CONCURRENT_SAFE,

    /**
     * The tool may run at the same time as neighbouring tools that declare the same value, but each call must get a
     * thread of its own that is created for that call and never reused. Use for a tool that registers a terminator
     * bound to its executing thread ({@code THREAD_INTERRUPT} / {@code EXTERNALLY_TERMINATED}) around a long blocking
     * call such as a subagent run ({@code Task}): a late interrupt then lands on a thread that dies with the call
     * rather than on a pooled worker's next task.
     *
     * <p>
     * Such a tool never goes onto the shared worker pool — the batch gate treats it exactly like {@link #SEQUENTIAL}.
     * Only {@link DedicatedThreadDispatcher}, installed when {@link DedicatedThreadConfig#isEnabled()} is set, fans a
     * run of consecutive {@code DEDICATED_THREAD} calls out onto dedicated threads; without it the tool runs in order
     * on the calling thread as before. The same contract as {@link #CONCURRENT_SAFE} applies to the tool's {@code
     * execute} method and its Pre/PostTool hooks: they must tolerate running concurrently with each other.
     */
    DEDICATED_THREAD
}
