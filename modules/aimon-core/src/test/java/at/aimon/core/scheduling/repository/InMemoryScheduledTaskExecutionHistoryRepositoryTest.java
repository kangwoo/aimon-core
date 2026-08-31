package at.aimon.core.scheduling.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.scheduling.ScheduledTaskExecutionHistory;
import at.aimon.core.scheduling.ScheduledTaskExecutionHistory.Status;
import at.aimon.core.scheduling.ScheduledTaskId;

class InMemoryScheduledTaskExecutionHistoryRepositoryTest {

    private InMemoryScheduledTaskExecutionHistoryRepository repo;
    private ScheduledTaskId taskA;
    private ScheduledTaskId taskB;

    @BeforeEach
    void setUp() {
        repo = new InMemoryScheduledTaskExecutionHistoryRepository();
        taskA = ScheduledTaskId.generate();
        taskB = ScheduledTaskId.generate();
    }

    private ScheduledTaskExecutionHistory makeHistory(String id, ScheduledTaskId taskId, Status status,
            Instant startedAt) {
        return ScheduledTaskExecutionHistory.builder().id(id).taskId(taskId).status(status).completedSteps(1)
                .totalSteps(1).startedAt(startedAt).completedAt(startedAt.plusSeconds(1)).build();
    }

    @Test
    void saveAndFindById() {
        ScheduledTaskExecutionHistory h = makeHistory("h1", taskA, Status.SUCCESS,
                Instant.parse("2025-01-01T00:00:00Z"));
        repo.save(h);

        assertThat(repo.findById("h1")).contains(h);
        assertThat(repo.findById("missing")).isEmpty();
    }

    @Test
    void findByTaskIdReturnsHistoriesOrderedByStartedAtDesc() {
        ScheduledTaskExecutionHistory older = makeHistory("h1", taskA, Status.SUCCESS,
                Instant.parse("2025-01-01T00:00:00Z"));
        ScheduledTaskExecutionHistory newer = makeHistory("h2", taskA, Status.FAILURE,
                Instant.parse("2025-01-02T00:00:00Z"));
        ScheduledTaskExecutionHistory other = makeHistory("h3", taskB, Status.SUCCESS,
                Instant.parse("2025-01-01T12:00:00Z"));
        repo.save(older);
        repo.save(newer);
        repo.save(other);

        assertThat(repo.findByTaskId(taskA)).containsExactly(newer, older);
        assertThat(repo.findByTaskId(taskB)).containsExactly(other);
    }

    @Test
    void findByTaskIdOrderByStartedAtDescAppliesLimit() {
        for (int i = 0; i < 5; i++) {
            repo.save(makeHistory("h" + i, taskA, Status.SUCCESS, Instant.parse("2025-01-0" + (i + 1) + "T00:00:00Z")));
        }

        assertThat(repo.findByTaskIdOrderByStartedAtDesc(taskA, 2)).hasSize(2)
                .extracting(ScheduledTaskExecutionHistory::getId).containsExactly("h4", "h3");
    }

    @Test
    void deleteByTaskIdRemovesOnlyMatchingTasks() {
        repo.save(makeHistory("h1", taskA, Status.SUCCESS, Instant.parse("2025-01-01T00:00:00Z")));
        repo.save(makeHistory("h2", taskA, Status.FAILURE, Instant.parse("2025-01-02T00:00:00Z")));
        repo.save(makeHistory("h3", taskB, Status.SUCCESS, Instant.parse("2025-01-01T12:00:00Z")));

        repo.deleteByTaskId(taskA);

        assertThat(repo.findByTaskId(taskA)).isEmpty();
        assertThat(repo.findByTaskId(taskB)).hasSize(1);
    }

    @Test
    void clearRemovesAllEntries() {
        repo.save(makeHistory("h1", taskA, Status.SUCCESS, Instant.parse("2025-01-01T00:00:00Z")));
        repo.save(makeHistory("h2", taskB, Status.FAILURE, Instant.parse("2025-01-02T00:00:00Z")));

        repo.clear();

        assertThat(repo.findByTaskId(taskA)).isEmpty();
        assertThat(repo.findByTaskId(taskB)).isEmpty();
    }
}
