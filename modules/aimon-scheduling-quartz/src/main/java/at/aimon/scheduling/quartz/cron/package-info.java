/*
 * Copyright 2025 the original author or authors.
 */

/**
 * The seam between the cron dialect AIMON stores and the one Quartz reads.
 *
 * <p>
 * {@link at.aimon.scheduling.quartz.cron.QuartzCronTranslator} is the only place in this module that turns a scheduled
 * task's expression into a Quartz one, and it translates in one direction only. The reverse direction is not missing by
 * omission: Quartz can say things — sub-minute schedules, {@code L}, {@code W}, {@code #} — that five fields cannot, so
 * a round trip would have to drop them.
 *
 * <p>
 * <b>Not every cron in this module comes through here.</b> The rewake trigger and the Dreamer schedule are configured
 * with Quartz expressions directly, by an operator writing a config file rather than by an agent registering a task,
 * and
 * they are documented as Quartz-native. They never reach {@code ScheduledTaskManager} and so never touch this package.
 * The dual dialect is deliberate; what was not deliberate was the third case, where a task stored in one dialect was
 * validated in the other.
 *
 * @see at.aimon.core.scheduling.cron.UnixCronExpression
 */
package at.aimon.scheduling.quartz.cron;
