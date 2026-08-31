/**
 * ReAct-loop re-entry tagging primitives.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package models the <em>continue</em> side of an agent's ReAct loop, complementing
 * {@link at.aimon.core.agent.budget.CompletionReason}, which models the <em>terminal</em> side. Each re-entry into a
 * new iteration is tagged with a {@link at.aimon.core.agent.loop.LoopTransition} so a run's shape can be reconstructed
 * from the trace without inspecting message contents.
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.loop.LoopTransitionReason} — why the loop re-entered (next iteration, drained queued
 * input, budget-forced compaction)
 * <li>{@link at.aimon.core.agent.loop.LoopTransition} — immutable {reason, iteration, note} value attached to the
 * per-iteration tracing span
 * </ul>
 *
 * <p>
 * These types are observation-only: they never participate in control flow. They live in a neutral (non-{@code impl})
 * package so both the main-agent loop driver and any future shared ReAct core can attach them without crossing the
 * {@code impl}-import boundary enforced by ArchUnit.
 */
package at.aimon.core.agent.loop;
