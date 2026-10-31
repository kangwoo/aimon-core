/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.scheduler;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.cron.UnixCronExpression;

/**
 * One fire of a scheduled task: which task, when the scheduler meant it to fire, and which cron slot it belongs to.
 *
 * <p>
 * The task id alone says which work to run but not which occurrence of it this is, and that is the gap a multi-node
 * deployment falls through. Two nodes holding their own triggers fire the same task for the same cron time a little
 * apart; a guard keyed on the task id only stops the second while the first is still running, so once the first has
 * finished and released its lease the second runs it again. Comparing the {@link #getSlot() slot} closes that: both
 * fires carry the same slot, whenever each arrives, so a guard that remembers the last slot that began refuses the
 * second.
 *
 * <ul>
 * <li>{@link #getScheduledFireTime()} — when the scheduler intended this fire. On time, a cron grid point; for a fire a
 * scheduler recovered after a misfire, the time it recovered at.</li>
 * <li>{@link #getSlot()} — that time folded back onto the cron grid, to the latest grid point at or before it. Equal on
 * every node for the same occurrence, late or not. Folded backwards on purpose: forwards would claim the next
 * occurrence and make it look already done.</li>
 * </ul>
 *
 * <p>
 * Both are empty for a fire that reached the manager without them — a scheduler that predates this type, or a caller
 * invoking {@code ScheduledTaskManager.executeTask(taskId)} directly. A guard must then fall back to what the task id
 * alone can do.
 */
public final class ScheduledFire {

    private final ScheduledTaskId taskId;
    private final Instant scheduledFireTime;
    private final Instant slot;

    private ScheduledFire(ScheduledTaskId taskId, Instant scheduledFireTime, Instant slot) {
        this.taskId = Objects.requireNonNull(taskId, "Task ID cannot be null");
        this.scheduledFireTime = scheduledFireTime;
        this.slot = slot;
    }

    /**
     * A fire whose scheduled time and slot are known.
     *
     * @param taskId
     *            the task (must not be null)
     * @param scheduledFireTime
     *            when the scheduler intended the fire (must not be null)
     * @param slot
     *            the cron grid point the fire belongs to (must not be null, and not after {@code scheduledFireTime})
     * @return the fire
     */
    public static ScheduledFire of(ScheduledTaskId taskId, Instant scheduledFireTime, Instant slot) {
        Objects.requireNonNull(scheduledFireTime, "Scheduled fire time cannot be null");
        Objects.requireNonNull(slot, "Slot cannot be null");
        if (slot.isAfter(scheduledFireTime)) {
            throw new IllegalArgumentException("Slot " + slot + " is after the scheduled fire time " + scheduledFireTime
                    + "; slots fold backwards");
        }
        return new ScheduledFire(taskId, scheduledFireTime, slot);
    }

    /**
     * A fire whose slot is computed by folding its scheduled time back onto {@code cron}'s grid in {@code zone}. When
     * the expression has no grid point at or before that time — it cannot, for a time the scheduler produced from it,
     * but the inputs come from outside — the scheduled time itself is the slot.
     *
     * @param taskId
     *            the task (must not be null)
     * @param scheduledFireTime
     *            when the scheduler intended the fire (must not be null)
     * @param cron
     *            the task's expression (must not be null)
     * @param zone
     *            the zone the scheduler evaluates the expression in (must not be null)
     * @return the fire
     */
    public static ScheduledFire onGrid(ScheduledTaskId taskId, Instant scheduledFireTime, UnixCronExpression cron,
            ZoneId zone) {
        Objects.requireNonNull(scheduledFireTime, "Scheduled fire time cannot be null");
        Objects.requireNonNull(cron, "Cron expression cannot be null");
        Objects.requireNonNull(zone, "Zone cannot be null");
        final Instant slot = cron.previousOrSameExecution(scheduledFireTime.atZone(zone)).map(ZonedDateTime::toInstant)
                .orElse(scheduledFireTime);
        return of(taskId, scheduledFireTime, slot);
    }

    /**
     * A fire known only by its task — no scheduled time, no slot.
     *
     * @param taskId
     *            the task (must not be null)
     * @return the fire
     */
    public static ScheduledFire untimed(ScheduledTaskId taskId) {
        return new ScheduledFire(taskId, null, null);
    }

    /**
     * Returns the task this fire runs.
     *
     * @return the task id
     */
    public ScheduledTaskId getTaskId() {
        return taskId;
    }

    /**
     * Returns when the scheduler intended this fire.
     *
     * @return the scheduled fire time, or empty if the scheduler did not say
     */
    public Optional<Instant> getScheduledFireTime() {
        return Optional.ofNullable(scheduledFireTime);
    }

    /**
     * Returns the cron grid point this fire belongs to.
     *
     * @return the slot, or empty if the scheduler did not say
     */
    public Optional<Instant> getSlot() {
        return Optional.ofNullable(slot);
    }

    /**
     * A key naming this occurrence of the task, the same on every node: {@code <taskId>@<slot epoch millis>}. For logs
     * and records about an occurrence. It is not a lease key: a lease per occurrence would let slot S+1 overlap a run
     * of
     * S, and would not refuse a misfire older than the last run. See {@code ScheduledExecutionGuard} for what to use.
     *
     * @return the occurrence key, or empty when the slot is unknown
     */
    public Optional<String> getOccurrenceKey() {
        return getSlot().map(s -> taskId.value() + '@' + s.toEpochMilli());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ScheduledFire other)) {
            return false;
        }
        return taskId.equals(other.taskId) && Objects.equals(scheduledFireTime, other.scheduledFireTime)
                && Objects.equals(slot, other.slot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, scheduledFireTime, slot);
    }

    @Override
    public String toString() {
        return "ScheduledFire{taskId='" + taskId + "'" + (slot != null ? ", slot=" + slot : "")
                + (scheduledFireTime != null && !scheduledFireTime.equals(slot) ? ", firedAt=" + scheduledFireTime : "")
                + '}';
    }
}
