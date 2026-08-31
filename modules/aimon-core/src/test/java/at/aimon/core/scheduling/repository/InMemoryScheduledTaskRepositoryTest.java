package at.aimon.core.scheduling.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;

class InMemoryScheduledTaskRepositoryTest {

    private InMemoryScheduledTaskRepository repo;
    private Principal alice;
    private Principal bob;

    @BeforeEach
    void setUp() {
        repo = new InMemoryScheduledTaskRepository();
        alice = Principal.user("alice", "Alice");
        bob = Principal.user("bob", "Bob");
    }

    private ScheduledTask makeTask(String name, Principal owner, boolean enabled) {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name(name).cronExpression("0 9 * * *")
                .routine(List.of(RoutineStep.of("echo", "{}"))).owner(owner)
                .boundRuntimeId(AgentRuntimeId.of("agent:" + name)).enabled(enabled).build();
    }

    @Test
    void saveAndFindById() {
        ScheduledTask task = makeTask("t1", alice, true);
        repo.save(task);

        assertThat(repo.findById(task.getId())).contains(task);
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertThat(repo.findById(ScheduledTaskId.generate())).isEmpty();
    }

    @Test
    void findAllReturnsAllSaved() {
        ScheduledTask t1 = makeTask("t1", alice, true);
        ScheduledTask t2 = makeTask("t2", alice, false);
        repo.save(t1);
        repo.save(t2);

        assertThat(repo.findAll()).containsExactlyInAnyOrder(t1, t2);
    }

    @Test
    void findByEnabledTrueFiltersByEnabledFlag() {
        ScheduledTask enabled = makeTask("t-enabled", alice, true);
        ScheduledTask disabled = makeTask("t-disabled", alice, false);
        repo.save(enabled);
        repo.save(disabled);

        assertThat(repo.findByEnabledTrue()).containsExactly(enabled);
    }

    @Test
    void findByOwnerFiltersByPrincipal() {
        ScheduledTask aliceTask = makeTask("t-alice", alice, true);
        ScheduledTask bobTask = makeTask("t-bob", bob, true);
        repo.save(aliceTask);
        repo.save(bobTask);

        assertThat(repo.findByOwner(alice)).containsExactly(aliceTask);
        assertThat(repo.findByOwner(bob)).containsExactly(bobTask);
    }

    @Test
    void findByOwnerAndEnabledTrueAppliesBothFilters() {
        ScheduledTask aliceEnabled = makeTask("t-1", alice, true);
        ScheduledTask aliceDisabled = makeTask("t-2", alice, false);
        ScheduledTask bobEnabled = makeTask("t-3", bob, true);
        repo.save(aliceEnabled);
        repo.save(aliceDisabled);
        repo.save(bobEnabled);

        assertThat(repo.findByOwnerAndEnabledTrue(alice)).containsExactly(aliceEnabled);
    }

    @Test
    void deleteByIdRemovesEntry() {
        ScheduledTask task = makeTask("t", alice, true);
        repo.save(task);

        repo.deleteById(task.getId());

        assertThat(repo.findById(task.getId())).isEmpty();
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void deleteByIdIsIdempotent() {
        ScheduledTaskId unknown = ScheduledTaskId.generate();
        repo.deleteById(unknown);
        // No exception, no state change
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void clearRemovesAllEntries() {
        repo.save(makeTask("t-1", alice, true));
        repo.save(makeTask("t-2", bob, true));

        repo.clear();

        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void saveOverwritesExistingTaskWithSameId() {
        ScheduledTask original = makeTask("t-orig", alice, true);
        repo.save(original);

        ScheduledTask updated = original.withEnabled(false);
        repo.save(updated);

        assertThat(repo.findById(original.getId())).contains(updated);
    }

    @Test
    void updateIfPresentReplacesAStoredTask() {
        ScheduledTask original = makeTask("t-update", alice, true);
        repo.save(original);

        ScheduledTask updated = original.withEnabled(false);

        assertThat(repo.updateIfPresent(updated)).isTrue();
        assertThat(repo.findById(original.getId())).contains(updated);
    }

    /**
     * The reason the method exists: unlike {@link InMemoryScheduledTaskRepository#save}, it must not recreate a task
     * that has been deleted. A scheduled run writes its task back when it finishes, and a cancellation in between
     * would otherwise be undone by that write.
     */
    @Test
    void updateIfPresentWritesNothingWhenTheTaskHasBeenDeleted() {
        ScheduledTask task = makeTask("t-deleted", alice, true);
        repo.save(task);
        repo.deleteById(task.getId());

        assertThat(repo.updateIfPresent(task.withEnabled(false))).isFalse();
        assertThat(repo.findById(task.getId())).isEmpty();
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void updateIfPresentWritesNothingForATaskThatWasNeverStored() {
        ScheduledTask neverStored = makeTask("t-unknown", alice, true);

        assertThat(repo.updateIfPresent(neverStored)).isFalse();
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void updateIfPresentRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> repo.updateIfPresent(null));
    }
}
