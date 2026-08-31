/**
 * Cooperative interrupt primitives for agent executions.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package defines the model used to propagate cancellation from the host (CLI SIGINT handler, {@code
 * QueuedInputPriority.NOW} arrival, budget exhaustion, parent agent cascade, system shutdown) down to the executor
 * and into individual tool calls.
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.interrupt.InterruptBehavior} — a {@code Tool}'s declaration of how it responds to
 * interruption.
 * <li>{@link at.aimon.core.agent.interrupt.InterruptReason} — classification of why an execution was interrupted;
 * surfaces in observability and in the resulting {@link at.aimon.core.agent.budget.CompletionReason}.
 * <li>{@link at.aimon.core.agent.interrupt.CancellationSignal} — read-side view of an execution's interrupt flag;
 * passed to tools through {@link at.aimon.core.agent.tool.ToolContext} so cooperative tools can poll.
 * <li>{@link at.aimon.core.agent.interrupt.InterruptCoordinator} — execution-scoped orchestrator that owns the signal
 * and fires registered {@link at.aimon.core.agent.interrupt.Terminator}s when tripped.
 * <li>{@link at.aimon.core.agent.interrupt.TerminatorRegistrar} — per-tool-execution handle that
 * {@link at.aimon.core.agent.interrupt.InterruptBehavior#THREAD_INTERRUPT} and
 * {@link at.aimon.core.agent.interrupt.InterruptBehavior#EXTERNALLY_TERMINATED} tools use to attach a kill callback.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>
 * All types in this package are safe for concurrent use. A {@link at.aimon.core.agent.interrupt.CancellationSignal}
 * is single-shot (monotonic transition to cancelled), listeners registered after the trip fire immediately on the
 * registering thread, and the coordinator serialises the trip itself so every listener observes the same
 * {@link at.aimon.core.agent.interrupt.InterruptReason}.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * A {@link at.aimon.core.agent.interrupt.InterruptCoordinator} is created by the executor at execution start and
 * disposed at execution end; its signal is never reused across executions. Per-tool registrars obtained from
 * {@link at.aimon.core.agent.interrupt.InterruptCoordinator#newTerminatorRegistrar()} are closed when the tool
 * returns so pending terminators do not leak into the next tool call.
 *
 * <p>
 * See {@code docs/design/agent-execution/interrupt.md} for the full design rationale and integration
 * sequence.
 */
package at.aimon.core.agent.interrupt;
