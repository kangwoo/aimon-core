/*
 * Copyright 2025 the original author or authors.
 */

/**
 * Reconciler — single conflict-resolution entry point for the memory layer
 * (design doc §6.4).
 *
 * <p>
 * Both the deriver (when a freshly minted observation collides with existing
 * ones) and the dreamer (when the random-walk strategy finds redundant
 * neighbours) call {@link at.aimon.core.memory.reconciler.Reconciler#evaluate}
 * with the candidate observation and its conflict set, and act on the returned
 * {@link at.aimon.core.memory.reconciler.ReconcileDecision}.
 *
 * <p>
 * The decision type is a sealed interface with four final-class variants —
 * {@code Accept}, {@code Replace}, {@code Merge}, {@code Reject} — per
 * {@code .claude/rules/code-style.md} (no {@code record}).
 */
package at.aimon.core.memory.reconciler;
