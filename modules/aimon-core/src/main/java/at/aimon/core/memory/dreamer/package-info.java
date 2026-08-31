/**
 * Dreamer subsystem — the long-running consolidator that walks each
 * workspace's observations, scores their information content, and merges
 * redundant clusters back into the {@link at.aimon.core.memory.ObservationStore}.
 *
 * <p>
 * Trigger lives outside this package: {@code aimon-scheduling-quartz}'s
 * {@code DreamerJob} resolves the workspace at fire time and calls
 * {@link at.aimon.core.memory.dreamer.DreamerEngine#consolidate}.
 *
 * <p>
 * See design doc §6.3 for the full pipeline.
 */
package at.aimon.core.memory.dreamer;
