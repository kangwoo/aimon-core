/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.cron.UnixCronExpression;

/**
 * Pins that a {@link ScheduledFire} survives a host persisting it into its own queue and rebuilding it on a worker:
 * the getters carry everything the factories need, and the rebuilt fire is equal, with the same occurrence key.
 */
class ScheduledFireTest {

    private final ScheduledTaskId taskId = ScheduledTaskId.of("task-roundtrip");

    @Test
    void aTimedFireRebuildsFromItsGetters() {
        final Instant slot = Instant.parse("2026-03-01T09:00:00Z");
        final ScheduledFire original = ScheduledFire.of(taskId, slot.plusSeconds(42), slot);

        final ScheduledFire rebuilt = rebuild(persist(original));

        assertThat(rebuilt).isEqualTo(original).hasSameHashCodeAs(original);
        assertThat(rebuilt.getOccurrenceKey()).isEqualTo(original.getOccurrenceKey())
                .contains("task-roundtrip@" + slot.toEpochMilli());
    }

    @Test
    void aFireFoldedOntoTheGridRebuildsWithoutTheCronExpression() {
        // A misfire recovered at 09:07 folds back to the 09:05 slot; the worker needs only the persisted instants.
        final ScheduledFire original = ScheduledFire.onGrid(taskId, Instant.parse("2026-03-01T09:07:13Z"),
                UnixCronExpression.parse("*/5 * * * *"), ZoneId.of("UTC"));

        final ScheduledFire rebuilt = rebuild(persist(original));

        assertThat(rebuilt).isEqualTo(original);
        assertThat(rebuilt.getSlot()).contains(Instant.parse("2026-03-01T09:05:00Z"));
        assertThat(rebuilt.getOccurrenceKey()).isEqualTo(original.getOccurrenceKey());
    }

    @Test
    void anUntimedFireRebuildsAsUntimed() {
        final ScheduledFire original = ScheduledFire.untimed(taskId);

        final ScheduledFire rebuilt = rebuild(persist(original));

        assertThat(rebuilt).isEqualTo(original);
        assertThat(rebuilt.getOccurrenceKey()).isEmpty();
    }

    /** What a host would store: plain strings, as in a queue row. */
    private static String[] persist(ScheduledFire fire) {
        return new String[]{fire.getTaskId().value(), fire.getScheduledFireTime().map(Instant::toString).orElse(null),
                fire.getSlot().map(Instant::toString).orElse(null)};
    }

    private static ScheduledFire rebuild(String[] row) {
        final ScheduledTaskId id = ScheduledTaskId.of(row[0]);
        if (row[1] == null) {
            return ScheduledFire.untimed(id);
        }
        return ScheduledFire.of(id, Instant.parse(row[1]), Instant.parse(row[2]));
    }
}
