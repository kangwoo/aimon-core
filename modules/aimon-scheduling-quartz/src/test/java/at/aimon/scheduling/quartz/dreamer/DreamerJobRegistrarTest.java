/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.quartz.dreamer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;

import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.WorkspaceStore;
import at.aimon.core.memory.dreamer.DreamerCycleSummary;
import at.aimon.core.memory.dreamer.DreamerEngine;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;

@DisplayName("DreamerJobRegistrar")
class DreamerJobRegistrarTest {

    private Scheduler scheduler;
    private WorkspaceStore workspaceStore;
    private DreamerEngine dreamerEngine;
    private DreamerJobRegistrar registrar;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "DreamerJobRegistrarTest-" + System.nanoTime());
        props.setProperty("org.quartz.scheduler.instanceId", "AUTO");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "2");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        scheduler = new StdSchedulerFactory(props).getScheduler();
        scheduler.start();
        workspaceStore = mock(WorkspaceStore.class);
        dreamerEngine = mock(DreamerEngine.class);
        registrar = new DreamerJobRegistrar(scheduler, workspaceStore, dreamerEngine);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (scheduler != null) {
            scheduler.shutdown(true);
        }
    }

    @Test
    @DisplayName("constructor publishes deps into scheduler context")
    void contextWired() throws Exception {
        assertThat(scheduler.getContext().get(DreamerJob.CONTEXT_KEY_WORKSPACE_STORE)).isSameAs(workspaceStore);
        assertThat(scheduler.getContext().get(DreamerJob.CONTEXT_KEY_DREAMER_ENGINE)).isSameAs(dreamerEngine);
    }

    @Test
    @DisplayName("register schedules a job under the workspace id")
    void registerSchedulesJob() throws Exception {
        registrar.register("ws-A", "0 * * * *");

        assertThat(registrar.isRegistered("ws-A")).isTrue();
        assertThat(scheduler.checkExists(JobKey.jobKey("ws-A", DreamerJobRegistrar.JOB_GROUP))).isTrue();
    }

    @Test
    @DisplayName("register takes the five-field dialect and hands Quartz the six-field translation")
    void registerTranslatesToQuartzDialect() throws Exception {
        registrar.register("ws-A", "*/5 * * * *");

        CronTrigger trigger = (CronTrigger) scheduler
                .getTrigger(TriggerKey.triggerKey("ws-A", DreamerJobRegistrar.TRIGGER_GROUP));
        assertThat(trigger.getCronExpression()).isEqualTo("0 */5 * * * ?");
    }

    @Test
    @DisplayName("register renumbers day-of-week — Friday is 5 here and 6 in Quartz")
    void registerRenumbersDayOfWeek() throws Exception {
        registrar.register("ws-A", "0 0 * * 5");

        CronTrigger trigger = (CronTrigger) scheduler
                .getTrigger(TriggerKey.triggerKey("ws-A", DreamerJobRegistrar.TRIGGER_GROUP));
        assertThat(trigger.getCronExpression()).isEqualTo("0 0 0 ? * 6");
    }

    @Test
    @DisplayName("register replaces an existing job for the same workspace")
    void registerReplacesExisting() {
        registrar.register("ws-A", "0 * * * *");
        // No exception → second register succeeds (replaces).
        registrar.register("ws-A", "0 0 * * *");
        assertThat(registrar.isRegistered("ws-A")).isTrue();
    }

    @Test
    @DisplayName("register rejects invalid cron expressions")
    void rejectsBadCron() {
        assertThatThrownBy(() -> registrar.register("ws-A", "not-a-cron"))
                .isInstanceOf(InvalidCronExpressionException.class);
    }

    @Test
    @DisplayName("register rejects a six-field Quartz expression — this side speaks five")
    void rejectsSixFieldQuartzCron() {
        assertThatThrownBy(() -> registrar.register("ws-A", "0 0 * * * ?"))
                .isInstanceOf(InvalidCronExpressionException.class);
    }

    @Test
    @DisplayName("register rejects an expression restricting both day fields — Quartz cannot express the union")
    void rejectsBothDayFieldsRestricted() {
        assertThatThrownBy(() -> registrar.register("ws-A", "0 0 1 * 1"))
                .isInstanceOf(InvalidCronExpressionException.class);
    }

    @Test
    @DisplayName("a rejected cron leaves the previously scheduled dreamer running")
    void rejectedCronDoesNotUnscheduleTheOldJob() {
        registrar.register("ws-A", "0 * * * *");

        assertThatThrownBy(() -> registrar.register("ws-A", "0 0 * * * ?"))
                .isInstanceOf(InvalidCronExpressionException.class);

        assertThat(registrar.isRegistered("ws-A")).isTrue();
    }

    @Test
    @DisplayName("unregister removes a scheduled job; idempotent")
    void unregisterRemovesJob() {
        registrar.register("ws-A", "0 * * * *");
        assertThat(registrar.isRegistered("ws-A")).isTrue();

        registrar.unregister("ws-A");
        assertThat(registrar.isRegistered("ws-A")).isFalse();

        registrar.unregister("ws-A"); // no-op, no exception
    }

    @Test
    @DisplayName("constructor rejects nulls")
    void constructorValidates() {
        assertThatThrownBy(() -> new DreamerJobRegistrar(null, workspaceStore, dreamerEngine))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DreamerJobRegistrar(scheduler, null, dreamerEngine))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DreamerJobRegistrar(scheduler, workspaceStore, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Fires the registered job directly rather than waiting for a tick. The five-field dialect's finest resolution is
     * one minute, so the once-per-second expression this used to lean on has no equivalent; {@code triggerJob} runs
     * the same {@code DreamerJob.execute}, resolving the workspace id from the job data and the engine from the
     * scheduler context, which is what this test is actually about.
     */
    @Test
    @DisplayName("end-to-end: firing the registered DreamerJob calls engine.consolidate")
    void endToEndFiring() throws Exception {
        Workspace workspace = Workspace.builder().id("ws-fire").build();
        when(workspaceStore.findById("ws-fire")).thenReturn(Optional.of(workspace));

        CountDownLatch fired = new CountDownLatch(1);
        AtomicReference<Workspace> seen = new AtomicReference<>();
        when(dreamerEngine.consolidate(any())).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            fired.countDown();
            return DreamerCycleSummary.builder().workspace(inv.getArgument(0)).subjectsWalked(0).build();
        });

        registrar.register("ws-fire", "* * * * *");
        scheduler.triggerJob(JobKey.jobKey("ws-fire", DreamerJobRegistrar.JOB_GROUP));

        assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get().getId()).isEqualTo("ws-fire");
    }
}
