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
    CONCURRENT_SAFE
}
