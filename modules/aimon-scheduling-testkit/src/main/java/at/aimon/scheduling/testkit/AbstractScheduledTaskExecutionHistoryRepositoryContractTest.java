/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.scheduling.ScheduledTaskExecutionHistory;
import at.aimon.core.scheduling.ScheduledTaskExecutionHistory.Status;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.repository.ScheduledTaskExecutionHistoryRepository;

/**
 * What every {@link ScheduledTaskExecutionHistoryRepository} owes the scheduling engine: records read back as
 * written, the outcome-unknown flag among them, every {@link Status} — {@code HALTED} included — survives the round
 * trip, newest-first ordering with a limit, and a per-task delete that leaves other tasks alone.
 *
 * <p>
 * A backend joins by subclassing and returning a fresh, empty repository from {@link #repository()}; tag the subclass
 * {@code @Tag("docker")} if it needs a daemon. Times are compared at millisecond precision, which every durable store
 * this was written against keeps.
 */
public abstract class AbstractScheduledTaskExecutionHistoryRepositoryContractTest {

    private static final Instant T0 = Instant.parse("2026-10-27T10:00:00Z");

    /**
     * @return the repository under test, empty, the same instance for the whole test
     */
    protected abstract ScheduledTaskExecutionHistoryRepository repository();

    @Test
    @DisplayName("a record reads back with every field as written")
    void roundTrip() {
        final ScheduledTaskExecutionHistory written = record("h1", "task-a", Status.PARTIAL, T0)
                .errorMessage("step 2 failed").outcomeUnknown(true).completedSteps(1).totalSteps(3).build();

        repository().save(written);

        assertThat(repository().findById("h1")).hasValueSatisfying(read -> {
            assertThat(read.getTaskId()).isEqualTo(ScheduledTaskId.of("task-a"));
            assertThat(read.getStatus()).isEqualTo(Status.PARTIAL);
            assertThat(read.getCompletedSteps()).isEqualTo(1);
            assertThat(read.getTotalSteps()).isEqualTo(3);
            assertThat(read.getErrorMessage()).hasValue("step 2 failed");
            assertThat(read.isOutcomeUnknown()).isTrue();
            assertThat(read.getStartedAt()).isEqualTo(T0);
            assertThat(read.getCompletedAt()).isEqualTo(T0.plusSeconds(5));
        });
        assertThat(repository().findById("absent")).isEmpty();
    }

    @Test
    @DisplayName("a record written without an unknown outcome reads back as known")
    void outcomeIsKnownByDefault() {
        repository().save(record("known", "task-a", Status.FAILURE, T0).build());

        assertThat(repository().findById("known"))
                .hasValueSatisfying(read -> assertThat(read.isOutcomeUnknown()).isFalse());
    }

    @Test
    @DisplayName("every status survives the round trip, including HALTED")
    void everyStatusRoundTrips() {
        int i = 0;
        for (Status status : Status.values()) {
            repository().save(record("s" + i, "task-s", status, T0.plusSeconds(i)).build());
            assertThat(repository().findById("s" + i))
                    .hasValueSatisfying(read -> assertThat(read.getStatus()).isEqualTo(status));
            i++;
        }
    }

    @Test
    @DisplayName("the recent query is newest first, limited, and per task")
    void recentIsNewestFirstAndLimited() {
        repository().save(record("old", "task-a", Status.SUCCESS, T0).build());
        repository().save(record("new", "task-a", Status.SUCCESS, T0.plus(2, ChronoUnit.HOURS)).build());
        repository().save(record("mid", "task-a", Status.FAILURE, T0.plus(1, ChronoUnit.HOURS)).build());
        repository().save(record("other", "task-b", Status.SUCCESS, T0.plus(3, ChronoUnit.HOURS)).build());

        final List<ScheduledTaskExecutionHistory> recent = repository()
                .findByTaskIdOrderByStartedAtDesc(ScheduledTaskId.of("task-a"), 2);

        assertThat(recent).extracting(ScheduledTaskExecutionHistory::getId).containsExactly("new", "mid");
        assertThat(repository().findByTaskId(ScheduledTaskId.of("task-a"))).hasSize(3);
    }

    @Test
    @DisplayName("deleting one task's history leaves the others'")
    void deleteByTaskIdIsPerTask() {
        repository().save(record("a1", "task-a", Status.SUCCESS, T0).build());
        repository().save(record("b1", "task-b", Status.SUCCESS, T0).build());

        repository().deleteByTaskId(ScheduledTaskId.of("task-a"));
        repository().deleteByTaskId(ScheduledTaskId.of("never-ran"));

        assertThat(repository().findByTaskId(ScheduledTaskId.of("task-a"))).isEmpty();
        assertThat(repository().findById("b1")).isPresent();

        repository().clear();
        assertThat(repository().findByTaskId(ScheduledTaskId.of("task-b"))).isEmpty();
    }

    private static ScheduledTaskExecutionHistory.Builder record(String id, String taskId, Status status,
            Instant startedAt) {
        return ScheduledTaskExecutionHistory.builder().id(id).taskId(ScheduledTaskId.of(taskId)).status(status)
                .startedAt(startedAt).completedAt(startedAt.plusSeconds(5));
    }
}
