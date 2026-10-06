package at.aimon.session.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentDefinitionVersion;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.SchedulingEngine;
import at.aimon.core.scheduling.SchedulingEngineBuilder;
import at.aimon.core.scheduling.repository.ScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.TaskScheduler;

/**
 * What every {@link ScheduledTaskRepository} owes the scheduling engine.
 *
 * <p>
 * The reference implementation is {@code InMemoryScheduledTaskRepository}, and it is the one {@code aimon-core} tests
 * its scheduling against. A durable backend has to give the same answers — including the ones the in-memory map gives
 * for free and a store has to work for: a task read back the same in every field as the one saved, owner queries
 * that follow {@link Principal#equals}, and writes after a run that neither bring a deleted task back nor undo what
 * changed while the run was in flight.
 *
 * <p>
 * A backend joins by subclassing and implementing {@link #repository()}, starting each test from an empty store. If
 * it keeps its data outside the process it also overrides {@link #reopened()} to hand back a second handle on the
 * same data, which is what lets {@link #storedTaskIsScheduledAgainAfterARestart} mean a restart.
 */
public abstract class AbstractScheduledTaskRepositoryContractTest {

    private static final int RACE_ROUNDS = 200;

    private final Principal alice = Principal.user("alice");
    private final Principal bob = Principal.user("bob");

    /**
     * @return the repository under test, empty at the start of each test
     */
    protected abstract ScheduledTaskRepository repository();

    /**
     * @return a handle on the same stored tasks as {@link #repository()}, as a process that started later would get
     *         one. The default returns the same instance, which is all an in-memory repository can offer
     */
    protected ScheduledTaskRepository reopened() {
        return repository();
    }

    @Test
    @DisplayName("a task with every field set is read back equal to the one saved")
    void fullyPopulatedTaskRoundTrips() {
        // Nanosecond instants and non-default durations on purpose: a store that keeps milliseconds, or that falls
        // back to a step's defaults, returns a task that looks right and does not equal the original.
        final ScheduledTask task = ScheduledTask.builder().id(ScheduledTaskId.generate()).name("nightly-report")
                .description("collects the day's incidents").cronExpression("15 2 * * 1-5").timezone("Asia/Seoul")
                .routine(List.of(RoutineStep.builder().id("collect").tool("Grep").toolParams("{\"pattern\":\"ERROR\"}")
                        .maxRetries(7).retryDelay(Duration.ofMillis(1500)).timeout(Duration.ofSeconds(42)).build(),
                        RoutineStep.builder().tool("Write").toolParams("").maxRetries(0).build()))
                .owner(Principal.service("reporter", "Report Service"))
                .boundRuntimeId(AgentRuntimeId.fromName("ops", "tenant-7"))
                .agentDefinitionVersion(AgentDefinitionVersion.of("v-2026-10-06")).enabled(true)
                .createdAt(Instant.parse("2026-10-06T01:02:03.123456789Z"))
                .lastExecutedAt(Instant.parse("2026-10-06T02:15:00.000000001Z")).build();

        repository().save(task);

        final ScheduledTask read = reopened().findById(task.getId()).orElseThrow();
        // Field by field, not equals(): ScheduledTask.equals compares the id alone and would pass for a store that
        // returned nothing else right.
        assertThat(read).usingRecursiveComparison().isEqualTo(task);
    }

    @Test
    @DisplayName("a task with only the required fields is read back equal, optionals still absent")
    void minimalTaskRoundTrips() {
        final ScheduledTask task = task(alice, "minimal", false);

        repository().save(task);

        final ScheduledTask read = reopened().findById(task.getId()).orElseThrow();
        assertThat(read).usingRecursiveComparison().isEqualTo(task);
        assertThat(read.getDescription()).isEmpty();
        assertThat(read.getAgentDefinitionVersion()).isEmpty();
        assertThat(read.getLastExecutedAt()).isEmpty();
    }

    @Test
    @DisplayName("saving the same id again replaces the stored task")
    void saveReplaces() {
        final ScheduledTask task = task(alice, "toggle", true);
        repository().save(task);

        repository().save(task.withEnabled(false));

        assertThat(repository().findById(task.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(repository().findAll()).hasSize(1);
    }

    @Test
    @DisplayName("updateIfPresent replaces a stored task and says so")
    void updateIfPresentReplacesAStoredTask() {
        final ScheduledTask task = task(alice, "ran", true);
        repository().save(task);
        final Instant ranAt = Instant.parse("2026-10-06T03:00:00.5Z");

        assertThat(repository().updateIfPresent(task.withLastExecutedAt(ranAt))).isTrue();

        assertThat(repository().findById(task.getId()).orElseThrow().getLastExecutedAt()).contains(ranAt);
    }

    @Test
    @DisplayName("updateIfPresent with nothing changed still reports the task as present")
    void updateIfPresentWithAnIdenticalTaskIsStillPresent() {
        final ScheduledTask task = task(alice, "unchanged", true);
        repository().save(task);

        assertThat(repository().updateIfPresent(task)).isTrue();
    }

    @Test
    @DisplayName("updateIfPresent does not bring back a task that was deleted")
    void updateIfPresentDoesNotRecreateADeletedTask() {
        // The cancel-during-run case: the run read its task, the owner cancelled, and the run's write-back arrives.
        final ScheduledTask task = task(alice, "cancelled", true);
        repository().save(task);
        repository().deleteById(task.getId());

        assertThat(repository().updateIfPresent(task.withLastExecutedAt(Instant.now()))).isFalse();

        assertThat(repository().findById(task.getId())).isEmpty();
        assertThat(repository().existsById(task.getId())).isFalse();
    }

    @Test
    @DisplayName("a delete racing updateIfPresent always wins in the end")
    void deleteRacingUpdateIfPresentNeverLeavesTheTaskBehind() throws Exception {
        // What a find-then-save implementation gets wrong: the update's presence check passes, the delete lands, and
        // the save recreates the task. Whichever order the two take, the task must be gone once both have returned.
        for (int round = 0; round < RACE_ROUNDS; round++) {
            final ScheduledTask task = task(alice, "race-" + round, true);
            repository().save(task);
            final CountDownLatch go = new CountDownLatch(1);
            final CompletableFuture<Void> update = CompletableFuture.runAsync(() -> {
                await(go);
                repository().updateIfPresent(task.withLastExecutedAt(Instant.now()));
            });
            final CompletableFuture<Void> delete = CompletableFuture.runAsync(() -> {
                await(go);
                repository().deleteById(task.getId());
            });
            go.countDown();
            CompletableFuture.allOf(update, delete).get(30, TimeUnit.SECONDS);

            assertThat(repository().findById(task.getId())).as("round %d", round).isEmpty();
        }
    }

    @Test
    @DisplayName("recordExecution sets when the task ran and leaves the rest as stored, not as the caller last saw it")
    void recordExecutionChangesOnlyTheExecutionTime() {
        // A run reads its task, runs, and writes back. In between, the owner disables the task. Writing the run's
        // copy back would enable it again; recording only the run must not.
        final ScheduledTask asTheRunReadIt = task(alice, "disabled-mid-run", true);
        repository().save(asTheRunReadIt);
        repository().save(asTheRunReadIt.withEnabled(false));
        final Instant ranAt = Instant.parse("2026-10-06T03:00:00.000000001Z");

        assertThat(repository().recordExecution(asTheRunReadIt.getId(), ranAt)).isTrue();

        final ScheduledTask stored = reopened().findById(asTheRunReadIt.getId()).orElseThrow();
        assertThat(stored.getLastExecutedAt()).contains(ranAt);
        assertThat(stored.isEnabled()).isFalse();
        assertThat(stored).usingRecursiveComparison().ignoringFields("lastExecutedAt")
                .isEqualTo(asTheRunReadIt.withEnabled(false));
    }

    @Test
    @DisplayName("recordExecution does not bring back a task that was deleted, or create one that never was")
    void recordExecutionDoesNotCreateATask() {
        final ScheduledTask task = task(alice, "cancelled", true);
        repository().save(task);
        repository().deleteById(task.getId());

        assertThat(repository().recordExecution(task.getId(), Instant.now())).isFalse();
        assertThat(repository().recordExecution(ScheduledTaskId.generate(), Instant.now())).isFalse();

        assertThat(repository().findAll()).isEmpty();
    }

    @Test
    @DisplayName("a delete racing recordExecution always wins in the end")
    void deleteRacingRecordExecutionNeverLeavesTheTaskBehind() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            final ScheduledTask task = task(alice, "race-" + round, true);
            repository().save(task);
            final CountDownLatch go = new CountDownLatch(1);
            final CompletableFuture<Void> record = CompletableFuture.runAsync(() -> {
                await(go);
                repository().recordExecution(task.getId(), Instant.now());
            });
            final CompletableFuture<Void> delete = CompletableFuture.runAsync(() -> {
                await(go);
                repository().deleteById(task.getId());
            });
            go.countDown();
            CompletableFuture.allOf(record, delete).get(30, TimeUnit.SECONDS);

            assertThat(repository().findById(task.getId())).as("round %d", round).isEmpty();
        }
    }

    @Test
    @DisplayName("countByOwner counts an owner's tasks, enabled or not, and nobody else's")
    void countByOwnerCountsEveryTaskOfThatOwner() {
        repository().save(task(alice, "alice-on", true));
        repository().save(task(alice, "alice-off", false));
        repository().save(task(bob, "bob-on", true));

        assertThat(repository().countByOwner(alice)).isEqualTo(2);
        assertThat(repository().countByOwner(Principal.user("alice", "Another Display Name"))).isEqualTo(2);
        assertThat(repository().countByOwner(Principal.service("alice", "Same Id, Other Type"))).isZero();
    }

    @Test
    @DisplayName("owner queries follow Principal equality: type and id, not the display name")
    void ownerQueriesFollowPrincipalEquality() {
        final ScheduledTask asUser = task(Principal.user("shared-id", "Old Name"), "user-task", true);
        final ScheduledTask asService = task(Principal.service("shared-id", "A Service"), "service-task", true);
        repository().save(asUser);
        repository().save(asService);

        // Same type and id under a different display name is the same owner; the same id under another type is not.
        assertThat(repository().findByOwner(Principal.user("shared-id", "New Name"))).extracting(ScheduledTask::getId)
                .containsExactly(asUser.getId());
        assertThat(repository().findByOwner(Principal.group("shared-id", "A Group"))).isEmpty();
    }

    @Test
    @DisplayName("the enabled queries leave disabled tasks out, with and without an owner")
    void enabledQueriesLeaveDisabledTasksOut() {
        final ScheduledTask aliceOn = task(alice, "alice-on", true);
        final ScheduledTask aliceOff = task(alice, "alice-off", false);
        final ScheduledTask bobOn = task(bob, "bob-on", true);
        repository().save(aliceOn);
        repository().save(aliceOff);
        repository().save(bobOn);

        assertThat(repository().findAll()).extracting(ScheduledTask::getId).containsExactlyInAnyOrder(aliceOn.getId(),
                aliceOff.getId(), bobOn.getId());
        assertThat(repository().findByEnabledTrue()).extracting(ScheduledTask::getId)
                .containsExactlyInAnyOrder(aliceOn.getId(), bobOn.getId());
        assertThat(repository().findByOwner(alice)).extracting(ScheduledTask::getId)
                .containsExactlyInAnyOrder(aliceOn.getId(), aliceOff.getId());
        assertThat(repository().findByOwnerAndEnabledTrue(alice)).extracting(ScheduledTask::getId)
                .containsExactly(aliceOn.getId());
    }

    @Test
    @DisplayName("deleting removes one task, deleting an absent one is not an error, and clear removes the rest")
    void deleteAndClear() {
        final ScheduledTask kept = task(alice, "kept", true);
        final ScheduledTask dropped = task(alice, "dropped", true);
        repository().save(kept);
        repository().save(dropped);

        repository().deleteById(dropped.getId());
        repository().deleteById(ScheduledTaskId.generate());

        assertThat(repository().existsById(dropped.getId())).isFalse();
        assertThat(repository().existsById(kept.getId())).isTrue();

        repository().clear();

        assertThat(repository().findAll()).isEmpty();
    }

    /**
     * The scenario a durable repository exists for: a task registered before a restart is scheduled after it.
     *
     * <p>
     * The second engine gets {@link #reopened()} and a scheduler of its own that has never heard of the task, so the
     * trigger it ends up holding can only have been rebuilt from what the repository kept. The disabled task is the
     * control: it is stored just the same and must not be scheduled.
     */
    @Test
    @DisplayName("a task registered before a restart is scheduled again after it")
    void storedTaskIsScheduledAgainAfterARestart() {
        final ScheduledTask enabled = task(alice, "survivor", true);
        final ScheduledTask disabled = task(alice, "paused", false);
        try (SchedulingEngine before = engine(repository(), new RecordingScheduler())) {
            before.start();
            before.getTaskManager().register(enabled);
            before.getTaskManager().register(disabled);
        }

        final RecordingScheduler afterRestart = new RecordingScheduler();
        try (SchedulingEngine after = engine(reopened(), afterRestart)) {
            after.start();

            assertThat(afterRestart.exists(enabled.getId())).isTrue();
            assertThat(afterRestart.exists(disabled.getId())).isFalse();
            assertThat(after.getTaskManager().getById(enabled.getId(), alice)).usingRecursiveComparison()
                    .isEqualTo(enabled);
        }
    }

    private static SchedulingEngine engine(ScheduledTaskRepository repository, TaskScheduler scheduler) {
        return SchedulingEngineBuilder.create().taskRepository(repository).taskSchedulerFactory(executor -> scheduler)
                .build();
    }

    private static ScheduledTask task(Principal owner, String name, boolean enabled) {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name(name).cronExpression("0 0 1 1 *")
                .owner(owner).boundRuntimeId(AgentRuntimeId.fromName("repository-contract"))
                .routine(List.of(RoutineStep.of("noop", "{}"))).enabled(enabled).build();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A scheduler that remembers what it was asked to hold; nothing ever fires. */
    private static final class RecordingScheduler implements TaskScheduler {

        private final Set<ScheduledTaskId> held = new HashSet<>();

        @Override
        public void scheduleRecurrently(ScheduledTaskId taskId, String cronExpression) {
            held.add(taskId);
        }

        @Override
        public void unschedule(ScheduledTaskId taskId) {
            held.remove(taskId);
        }

        @Override
        public boolean exists(ScheduledTaskId taskId) {
            return held.contains(taskId);
        }

        @Override
        public void clear() {
            held.clear();
        }

        @Override
        public void start() {
            // Nothing to start: no thread fires anything.
        }

        @Override
        public void shutdown() {
            // Nothing to stop.
        }
    }
}
