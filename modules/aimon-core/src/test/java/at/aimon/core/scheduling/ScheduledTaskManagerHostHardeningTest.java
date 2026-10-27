/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.cron.UnixCronExpression;
import at.aimon.core.scheduling.event.ScheduledTaskEventListener;
import at.aimon.core.scheduling.event.TaskDisabledEvent;
import at.aimon.core.scheduling.exception.UnresolvableBoundRuntimeException;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.ScheduledFire;
import at.aimon.core.scheduling.scheduler.ScheduledTaskExecutor;
import at.aimon.core.scheduling.scheduler.TaskScheduler;

/**
 * The host-facing hardening of scheduling: bound runtimes checked at registration and at fire time, the fire's slot
 * reaching the guard, and per-node triggers converging on the repository.
 */
class ScheduledTaskManagerHostHardeningTest {

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private final DefaultAgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
    private final InMemoryScheduledTaskRepository repository = new InMemoryScheduledTaskRepository();
    private final InMemoryScheduledTaskExecutionHistoryRepository history = new InMemoryScheduledTaskExecutionHistoryRepository();
    private final AgentRuntimeId runtimeId = AgentRuntimeId.fromName("hardening");

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
    }

    @Nested
    @DisplayName("bound runtime at registration (S1)")
    class RegistrationCheck {

        @Test
        @DisplayName("a task bound to a runtime nobody registered is refused and leaves nothing behind")
        void unresolvableRuntimeIsRefused() {
            final SchedulingEngine engine = engine(SchedulingEngineBuilder.create());

            assertThatThrownBy(() -> engine.getTaskManager().register(task()))
                    .isInstanceOf(UnresolvableBoundRuntimeException.class).hasMessageContaining("agent:hardening");
            assertThat(repository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("a task bound to a registered runtime is accepted")
        void resolvableRuntimeIsAccepted() {
            registerRuntime();
            final SchedulingEngine engine = engine(SchedulingEngineBuilder.create());

            engine.getTaskManager().register(task());

            assertThat(repository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("the check can be turned off")
        void checkCanBeTurnedOff() {
            final SchedulingEngine engine = engine(SchedulingEngineBuilder.create().validateBoundRuntime(false));

            engine.getTaskManager().register(task());

            assertThat(repository.findAll()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("bound runtime at fire time (S1)")
    class FireTimePolicy {

        @Test
        @DisplayName("KEEP_SCHEDULED, the default, runs the fire and keeps the task enabled")
        void keepScheduledIsTheDefault() {
            registerRuntime();
            final SchedulingEngine engine = engine(SchedulingEngineBuilder.create());
            final ScheduledTask task = engine.getTaskManager().register(task());
            registry.unregister(runtimeId);

            engine.getTaskManager().executeTask(task.getId());

            assertThat(repository.findById(task.getId()).orElseThrow().isEnabled()).isTrue();
            assertThat(history.findByTaskIdOrderByStartedAtDesc(task.getId(), 5)).singleElement()
                    .satisfies(h -> assertThat(h.getStatus()).isEqualTo(ScheduledTaskExecutionHistory.Status.FAILURE));
        }

        @Test
        @DisplayName("DISABLE skips the fire, disables and unschedules the task, and says so with an event")
        void disableDisablesTheTask() {
            registerRuntime();
            final RecordingScheduler scheduler = new RecordingScheduler();
            final SchedulingEngine engine = engine(SchedulingEngineBuilder.create().taskScheduler(scheduler)
                    .unresolvableRuntimePolicy(UnresolvableRuntimePolicy.DISABLE));
            final List<TaskDisabledEvent> disabled = new CopyOnWriteArrayList<>();
            engine.addEventListener(new ScheduledTaskEventListener() {
                @Override
                public void onTaskDisabled(TaskDisabledEvent event) {
                    disabled.add(event);
                }
            });
            final ScheduledTask task = engine.getTaskManager().register(task());
            registry.unregister(runtimeId);

            engine.getTaskManager().executeTask(task.getId());

            assertThat(repository.findById(task.getId()).orElseThrow().isEnabled()).isFalse();
            assertThat(scheduler.ids).doesNotContain(task.getId());
            assertThat(history.findByTaskIdOrderByStartedAtDesc(task.getId(), 5)).isEmpty();
            assertThat(disabled).singleElement()
                    .satisfies(event -> assertThat(event.getReason()).contains("agent:hardening"));
        }
    }

    @Nested
    @DisplayName("one node of several")
    class MultiNode {

        @Test
        @DisplayName("a fire whose runtime this node lacks is skipped before the guard, leaving the occurrence free")
        void unresolvableFireDoesNotClaimTheOccurrence() {
            final List<ScheduledFire> asked = new CopyOnWriteArrayList<>();
            final ScheduledExecutionGuard guard = new ScheduledExecutionGuard() {
                @Override
                public Optional<ExecutionLease> tryBegin(ScheduledTaskId taskId) {
                    return Optional.of(() -> {
                    });
                }

                @Override
                public Optional<ExecutionLease> tryBegin(ScheduledFire fire) {
                    asked.add(fire);
                    return tryBegin(fire.getTaskId());
                }
            };
            final SchedulingEngine engine = engine(SchedulingEngineBuilder.create().multiNode(true)
                    .validateBoundRuntime(false).executionGuard(guard).taskScheduler(new RecordingScheduler()));
            final ScheduledTask task = engine.getTaskManager().register(task());

            engine.getTaskManager().executeTask(task.getId());

            assertThat(asked).isEmpty();
            assertThat(history.findByTaskIdOrderByStartedAtDesc(task.getId(), 5)).isEmpty();
        }

        @Test
        @DisplayName("DISABLE is refused, since one node's miss would disable the task everywhere")
        void disableIsRefused() {
            assertThatThrownBy(() -> SchedulingEngineBuilder.create().multiNode(true)
                    .unresolvableRuntimePolicy(UnresolvableRuntimePolicy.DISABLE).build())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("DISABLE");
        }
    }

    @Nested
    @DisplayName("the fire's slot reaches the guard (S3)")
    class FireSlot {

        @Test
        @DisplayName("executeFire hands the guard the fire, and a guard can refuse a slot that already ran")
        void guardSeesTheSlot() {
            registerRuntime();
            final List<ScheduledFire> asked = new CopyOnWriteArrayList<>();
            final Set<String> ran = new HashSet<>();
            final ScheduledExecutionGuard slotGuard = new ScheduledExecutionGuard() {
                @Override
                public Optional<ExecutionLease> tryBegin(ScheduledTaskId taskId) {
                    throw new AssertionError("the manager must call the fire overload");
                }

                @Override
                public synchronized Optional<ExecutionLease> tryBegin(ScheduledFire fire) {
                    asked.add(fire);
                    return ran.add(fire.getOccurrenceKey().orElseThrow()) ? Optional.of(() -> {
                    }) : Optional.empty();
                }
            };
            final SchedulingEngine engine = engine(SchedulingEngineBuilder.create().executionGuard(slotGuard));
            final ScheduledTask task = engine.getTaskManager().register(task());
            final Instant slot = Instant.parse("2026-10-27T10:00:00Z");

            final ScheduledTaskExecutor executor = engine.getTaskManager().asExecutor();
            executor.execute(ScheduledFire.of(task.getId(), slot, slot));
            // The same occurrence, arriving late on another node after the first run released its lease.
            executor.execute(ScheduledFire.of(task.getId(), slot.plusSeconds(7), slot));

            assertThat(asked).hasSize(2);
            assertThat(history.findByTaskIdOrderByStartedAtDesc(task.getId(), 5)).hasSize(1);
        }

        @Test
        @DisplayName("an existing task-id guard keeps working through the default bridge")
        void taskIdGuardStillWorks() {
            final ScheduledExecutionGuard guard = new InMemoryScheduledExecutionGuard();
            final ScheduledTaskId id = ScheduledTaskId.of("t");

            try (ScheduledExecutionGuard.ExecutionLease lease = guard.tryBegin(ScheduledFire.untimed(id))
                    .orElseThrow()) {
                assertThat(guard.tryBegin(ScheduledFire.untimed(id))).isEmpty();
            }
            assertThat(guard.tryBegin(ScheduledFire.untimed(id))).isPresent();
        }

        @Test
        @DisplayName("a late fire folds back onto the grid point it belongs to, never forward")
        void lateFireFoldsBackwards() {
            final UnixCronExpression cron = UnixCronExpression.parse("*/15 * * * *");
            final Instant onTime = Instant.parse("2026-10-27T10:15:00Z");

            final ScheduledFire late = ScheduledFire.onGrid(ScheduledTaskId.of("t"),
                    Instant.parse("2026-10-27T10:29:59Z"), cron, ZoneOffset.UTC);
            final ScheduledFire exact = ScheduledFire.onGrid(ScheduledTaskId.of("t"), onTime, cron, ZoneOffset.UTC);

            assertThat(late.getSlot()).hasValue(onTime);
            assertThat(exact.getSlot()).hasValue(onTime);
            assertThat(late.getOccurrenceKey()).isEqualTo(exact.getOccurrenceKey());
            assertThat(cron.previousOrSameExecution(ZonedDateTime.parse("2026-10-27T10:30:00Z")))
                    .hasValue(ZonedDateTime.parse("2026-10-27T10:30:00Z"));
        }

        @Test
        @DisplayName("a slot after its fire time is rejected")
        void slotMayNotBeAfterTheFire() {
            final Instant t = Instant.parse("2026-10-27T10:00:00Z");

            assertThatThrownBy(() -> ScheduledFire.of(ScheduledTaskId.of("t"), t, t.plusSeconds(60)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("per-node triggers converge on the repository (S4)")
    class Reconcile {

        @Test
        @DisplayName("reconcile adds the trigger of a task another node registered and removes one another node cancelled")
        void reconcileConverges() {
            final RecordingScheduler scheduler = new RecordingScheduler();
            final SchedulingEngine engine = engine(
                    SchedulingEngineBuilder.create().validateBoundRuntime(false).taskScheduler(scheduler));
            final ScheduledTask registeredElsewhere = task();
            repository.save(registeredElsewhere);
            final ScheduledTaskId cancelledElsewhere = ScheduledTaskId.of("ghost");
            scheduler.scheduleRecurrently(cancelledElsewhere, "* * * * *");

            final int changed = engine.getTaskManager().reconcile();

            assertThat(changed).isEqualTo(2);
            assertThat(scheduler.ids).containsExactly(registeredElsewhere.getId());
            assertThat(engine.getTaskManager().reconcile()).as("idempotent").isZero();
        }

        @Test
        @DisplayName("a scheduler that cannot list only gets missing triggers added")
        void unlistableSchedulerOnlyAdds() {
            final RecordingScheduler scheduler = new RecordingScheduler().unlistable();
            final SchedulingEngine engine = engine(
                    SchedulingEngineBuilder.create().validateBoundRuntime(false).taskScheduler(scheduler));
            repository.save(task());
            scheduler.scheduleRecurrently(ScheduledTaskId.of("ghost"), "* * * * *");

            assertThat(engine.getTaskManager().reconcile()).isEqualTo(1);
            assertThat(scheduler.ids).contains(ScheduledTaskId.of("ghost"));
        }

        @Test
        @DisplayName("the engine reconciles periodically only on one of several nodes, per-node triggers, supplied guard")
        void engineReconcilesOnlyWhereSafe() {
            final SchedulingEngine single = engine(
                    SchedulingEngineBuilder.create().taskScheduler(new RecordingScheduler()));
            final SchedulingEngine noGuard = engine(
                    SchedulingEngineBuilder.create().multiNode(true).taskScheduler(new RecordingScheduler()));
            final SchedulingEngine guarded = engine(
                    SchedulingEngineBuilder.create().multiNode(true).executionGuard(distributedLooking())
                            .taskScheduler(new RecordingScheduler()).reconcileInterval(Duration.ofSeconds(5)));
            final SchedulingEngine nodeLocalGuard = engine(SchedulingEngineBuilder.create().multiNode(true)
                    .executionGuard(new InMemoryScheduledExecutionGuard()).taskScheduler(new RecordingScheduler()));
            final SchedulingEngine clusterWide = engine(SchedulingEngineBuilder.create().multiNode(true)
                    .executionGuard(distributedLooking()).taskScheduler(new RecordingScheduler().clusterWide()));

            assertThat(single.reconcileInterval()).isZero();
            assertThat(noGuard.reconcileInterval()).isZero();
            assertThat(guarded.reconcileInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(nodeLocalGuard.reconcileInterval()).as("a node-local guard sees no other node").isZero();
            assertThat(clusterWide.reconcileInterval()).isZero();
        }
    }

    @Nested
    @DisplayName("the task's timezone reaches the scheduler")
    class Timezone {

        @Test
        @DisplayName("a task with a timezone is scheduled in that zone, one without in the scheduler's own")
        void zoneIsPassedOn() {
            final RecordingScheduler scheduler = new RecordingScheduler();
            final SchedulingEngine engine = engine(
                    SchedulingEngineBuilder.create().validateBoundRuntime(false).taskScheduler(scheduler));
            final ScheduledTask seoul = engine.getTaskManager().register(task("Asia/Seoul"));
            final ScheduledTask local = engine.getTaskManager().register(task(null));

            assertThat(scheduler.zones).containsEntry(seoul.getId(), java.time.ZoneId.of("Asia/Seoul"));
            assertThat(scheduler.zones).doesNotContainKey(local.getId());
            assertThat(scheduler.ids).contains(seoul.getId(), local.getId());
        }

        @Test
        @DisplayName("an unknown zone is refused at registration and nothing is stored")
        void unknownZoneIsRefused() {
            final SchedulingEngine engine = engine(SchedulingEngineBuilder.create().validateBoundRuntime(false)
                    .taskScheduler(new RecordingScheduler()));

            assertThatThrownBy(() -> engine.getTaskManager().register(task("Mars/Olympus")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Mars/Olympus");
            assertThat(repository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("a scheduler that cannot take a zone refuses a foreign one rather than ignoring it")
        void zonelessSchedulerRefusesAForeignZone() {
            final ZonelessScheduler scheduler = new ZonelessScheduler();
            final SchedulingEngine engine = engine(
                    SchedulingEngineBuilder.create().validateBoundRuntime(false).taskScheduler(scheduler));
            final String foreign = java.time.ZoneId.systemDefault().getId().equals("Pacific/Kiritimati")
                    ? "Asia/Seoul"
                    : "Pacific/Kiritimati";

            assertThatThrownBy(() -> engine.getTaskManager().register(task(foreign)))
                    .isInstanceOf(at.aimon.core.scheduling.exception.TaskSchedulerException.class)
                    .hasMessageContaining(foreign);
            assertThat(repository.findAll()).as("the registration is undone").isEmpty();

            engine.getTaskManager().register(task(java.time.ZoneId.systemDefault().getId()));
            assertThat(scheduler.scheduled).isEqualTo(1);
        }

        private ScheduledTask task(String timezone) {
            return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("t").cronExpression("0 9 * * *")
                    .timezone(timezone).routine(List.of(RoutineStep.of("Missing", "{}"))).owner(Principal.user("alice"))
                    .boundRuntimeId(runtimeId).enabled(true).build();
        }
    }

    /** A guard that is not one of the known node-local ones, standing in for a distributed implementation. */
    private static ScheduledExecutionGuard distributedLooking() {
        final ScheduledExecutionGuard local = new InMemoryScheduledExecutionGuard();
        return local::tryBegin;
    }

    private SchedulingEngine engine(SchedulingEngineBuilder builder) {
        final SchedulingEngine engine = builder.taskRepository(repository).historyRepository(history)
                .agentRuntimeRegistry(registry).build();
        toClose.add(engine);
        engine.start();
        return engine;
    }

    private void registerRuntime() {
        final Agent agent = DefaultAgent.builder().name("hardening").systemPrompt("test").build();
        registry.register(new AgentRuntime() {
            @Override
            public AgentRuntimeId getId() {
                return runtimeId;
            }

            @Override
            public Agent getAgent() {
                return agent;
            }

            @Override
            public List<Tool> getAvailableTools() {
                return List.of();
            }
        });
    }

    private ScheduledTask task() {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("t").cronExpression("0 3 * * *")
                .routine(List.of(RoutineStep.withRetry("Missing", "{}", 0, Duration.ofMillis(1))))
                .owner(Principal.user("alice")).boundRuntimeId(runtimeId).enabled(true).build();
    }

    /** Holds triggers in a set; fires nothing. */
    private static final class RecordingScheduler implements TaskScheduler {
        final Set<ScheduledTaskId> ids = new HashSet<>();
        final java.util.Map<ScheduledTaskId, java.time.ZoneId> zones = new java.util.HashMap<>();
        private boolean listable = true;
        private boolean clusterWide;

        RecordingScheduler unlistable() {
            listable = false;
            return this;
        }

        RecordingScheduler clusterWide() {
            clusterWide = true;
            return this;
        }

        @Override
        public synchronized void scheduleRecurrently(ScheduledTaskId taskId, String cronExpression) {
            ids.add(taskId);
        }

        @Override
        public synchronized void scheduleRecurrently(ScheduledTaskId taskId, String cronExpression,
                java.time.ZoneId zone) {
            ids.add(taskId);
            zones.put(taskId, zone);
        }

        @Override
        public synchronized void unschedule(ScheduledTaskId taskId) {
            ids.remove(taskId);
            zones.remove(taskId);
        }

        @Override
        public synchronized boolean exists(ScheduledTaskId taskId) {
            return ids.contains(taskId);
        }

        @Override
        public synchronized Optional<Set<ScheduledTaskId>> scheduledTaskIds() {
            return listable ? Optional.of(Set.copyOf(ids)) : Optional.empty();
        }

        @Override
        public synchronized void clear() {
            ids.clear();
        }

        @Override
        public void start() {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public boolean isClusterWide() {
            return clusterWide;
        }
    }

    /** A scheduler written before zones reached the interface: it overrides only the two-argument method. */
    private static final class ZonelessScheduler implements TaskScheduler {
        int scheduled;

        @Override
        public void scheduleRecurrently(ScheduledTaskId taskId, String cronExpression) {
            scheduled++;
        }

        @Override
        public void unschedule(ScheduledTaskId taskId) {
        }

        @Override
        public boolean exists(ScheduledTaskId taskId) {
            return false;
        }

        @Override
        public void clear() {
        }

        @Override
        public void start() {
        }

        @Override
        public void shutdown() {
        }
    }
}
