/**
 * Mid-turn user input queue primitives for agent executions.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package defines the storage primitives and the facade API for a command queue that buffers user (or sub-agent
 * originated) input while an agent is already mid-turn. The queue lets callers decide when a buffered message should be
 * injected into the ReAct loop without dropping messages that arrive during long-running tool executions.
 *
 * <h2>Layering</h2>
 *
 * <p>
 * The package is split into two layers with deliberately separated responsibilities:
 * <ul>
 * <li><b>Storage layer</b> — {@link at.aimon.core.agent.queue.MessageQueueRepository} plus its default
 * in-memory implementation {@link at.aimon.core.agent.queue.InMemoryMessageQueueRepository}. This is the
 * swap-point for distributed backends (Redis, Mongo, …) and owns FIFO-within-priority ordering.
 * <li><b>Facade layer</b> — {@link at.aimon.core.agent.queue.MessageQueueManager} plus its default implementation
 * {@link at.aimon.core.agent.queue.DefaultMessageQueueManager}. Non-storage callers (Orca ReAct loop, REPL) depend on
 * this interface. It adds observability ({@link at.aimon.core.agent.queue.MessageQueueListener}) and a batch drain
 * primitive suitable for mid-turn injection at iteration boundaries.
 * </ul>
 *
 * <h2>Key Concepts</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.queue.QueuedInput} — immutable envelope around a single piece of buffered input.
 * <li>{@link at.aimon.core.agent.queue.QueuedInputPriority} — ordering tier that drives dequeue semantics
 * ({@code NOW} first, then {@code NEXT}, then {@code LATER}).
 * <li>{@link at.aimon.core.agent.queue.MessageQueueRepository} — storage abstraction.
 * <li>{@link at.aimon.core.agent.queue.InMemoryMessageQueueRepository} — thread-safe in-memory reference
 * implementation suitable for single-JVM deployments and tests.
 * <li>{@link at.aimon.core.agent.queue.MessageQueueManager} — facade with listener fan-out and
 * batch-drain semantics; the entry point for Orca/REPL integration.
 * <li>{@link at.aimon.core.agent.queue.MessageQueueListener} — single-method observer carrying an
 * {@link at.aimon.core.agent.queue.MessageQueueListener.Event Event}
 * whose {@link at.aimon.core.agent.queue.MessageQueueListener.ChangeType ChangeType} discriminates
 * {@code ENQUEUED} / {@code DRAINED} / {@code REMOVED}.
 * <li>{@link at.aimon.core.agent.queue.LoggingMessageQueueListener} — reference observer that logs every event at
 * DEBUG and keeps per-change-type counters; register it as the default metrics source or use it as a template for a
 * Micrometer/OpenTelemetry binding.
 * </ul>
 *
 * <h2>Priority Semantics</h2>
 *
 * <p>
 * Within a given priority tier messages are ordered by insertion (FIFO). Across tiers, higher priority messages surface
 * first: {@code NOW} &rarr; {@code NEXT} &rarr; {@code LATER}. Callers typically filter the queue by
 * {@code agentRuntimeId} so the main agent and its sub-agents do not cross-consume each other's inputs.
 * The manager's batch drain
 * ({@link at.aimon.core.agent.queue.MessageQueueManager#drainForInjection drainForInjection}) honours the
 * caller-provided {@code maxPriority} cap so lower-priority (e.g. {@code LATER}) entries remain queued until an
 * explicit consumer asks for them.
 *
 * <h2>Multi-Instance Readiness</h2>
 *
 * <p>
 * Per the AIMON multi-instance design rule, the queue is defined as an interface
 * ({@link at.aimon.core.agent.queue.MessageQueueRepository}) so a distributed backing store (Redis, Mongo, etc.) can
 * be swapped in without changes to callers. The in-memory implementation is the default reference backend; the manager
 * facade delegates entirely to whichever repository is injected.
 *
 * <h2>Scope</h2>
 *
 * <p>
 * This package intentionally stops at storage and the manager facade. The ReAct-loop injection hook and REPL
 * integration live in follow-up iterations (see CQ-03 … CQ-05 in the pipeline adoption plan).
 */
package at.aimon.core.agent.queue;
