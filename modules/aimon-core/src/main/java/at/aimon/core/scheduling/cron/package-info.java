/*
 * Copyright 2025 the original author or authors.
 */

/**
 * The cron dialect AIMON stores, and the one place that knows it.
 *
 * <p>
 * A scheduled task's {@code cronExpression} is a string, and a string does not say which cron it is written in. Five
 * fields or six, Sunday {@code 0} or Sunday {@code 1}, {@code ?} allowed or not — the dialects disagree on all three,
 * and disagree quietly: a five-field expression handed to a six-field parser is not rejected for being short, its
 * fields
 * shift left by one and it becomes an expression about a different thing.
 * {@link at.aimon.core.scheduling.cron.UnixCronExpression}
 * exists so that the framework has one answer to "which cron is this", and so that a backend speaking another dialect
 * has something to translate <em>from</em> rather than a string to guess at.
 *
 * <p>
 * Backends translate on the way in and never on the way out. The direction is not a preference — it is the only
 * lossless
 * one, since the richer dialects have constructs (seconds, {@code L}, {@code W}, {@code #}) that five fields cannot
 * say.
 *
 * @see at.aimon.core.scheduling.ScheduledTaskManager
 * @see at.aimon.core.scheduling.scheduler.TaskScheduler
 */
package at.aimon.core.scheduling.cron;
