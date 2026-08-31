/*
 * Copyright 2025 the original author or authors.
 */

/**
 * Quartz-based implementation of the AIMON scheduling system.
 *
 * <p>
 * This package provides a Quartz Scheduler integration for the AIMON task scheduling system, offering enterprise-grade
 * scheduling capabilities including:
 * <ul>
 * <li>Persistent job storage via JDBC</li>
 * <li>Clustering support for distributed environments</li>
 * <li>Misfire handling and job recovery</li>
 * <li>Cron-based scheduling</li>
 * </ul>
 *
 * <p>
 * Main classes:
 * <ul>
 * <li>{@link at.aimon.scheduling.quartz.QuartzTaskScheduler} - TaskScheduler implementation using Quartz</li>
 * <li>{@link at.aimon.scheduling.quartz.QuartzTaskSchedulerBuilder} - Builder for configuring QuartzTaskScheduler</li>
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * // Simple usage with builder
 * QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create()
 *         .taskExecutor(taskId -> taskManager.executeTask(taskId)).build();
 * scheduler.start();
 * scheduler.scheduleRecurrently(ScheduledTaskId.of("task1"), "0 * * * *");
 *
 * // Clustered setup with JDBC
 * QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create().instanceName("ClusteredScheduler")
 *         .taskExecutor(taskId -> taskManager.executeTask(taskId))
 *         .jdbcJobStore("jdbc:postgresql://localhost/quartz", "org.postgresql.Driver").clustered(true).build();
 * }
 * </pre>
 *
 * @see at.aimon.core.scheduling.scheduler.TaskScheduler
 */
package at.aimon.scheduling.quartz;
