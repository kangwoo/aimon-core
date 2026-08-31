/*
 * Copyright 2025 the original author or authors.
 */

/**
 * Quartz-driven trigger for the memory dreamer (design doc §6.3).
 *
 * <p>
 * {@link at.aimon.scheduling.quartz.dreamer.DreamerJob} is the cron-fired
 * adapter that calls {@code DreamerEngine.consolidate(Workspace)}; the
 * {@link at.aimon.scheduling.quartz.dreamer.DreamerJobRegistrar} wires its
 * dependencies into the {@code SchedulerContext} and (re)schedules per-workspace
 * jobs. Both are intended to be created once at application start, before the
 * scheduler runs, and live for the JVM's lifetime — the dreamer is long-lived
 * by design.
 */
package at.aimon.scheduling.quartz.dreamer;
