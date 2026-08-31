/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.quartz.dreamer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerContext;
import org.quartz.SchedulerException;

import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.WorkspaceStore;
import at.aimon.core.memory.dreamer.DreamerCycleSummary;
import at.aimon.core.memory.dreamer.DreamerEngine;

@DisplayName("DreamerJob")
class DreamerJobTest {

    private static final String WORKSPACE_ID = "ws-1";
    private static final Workspace WORKSPACE = Workspace.builder().id(WORKSPACE_ID).build();

    private DreamerJob job;
    private WorkspaceStore workspaceStore;
    private DreamerEngine dreamerEngine;

    @BeforeEach
    void setUp() {
        job = new DreamerJob();
        workspaceStore = mock(WorkspaceStore.class);
        dreamerEngine = mock(DreamerEngine.class);
    }

    @Test
    @DisplayName("happy path: resolves workspace from store and calls engine.consolidate")
    void executesConsolidateCycle() throws Exception {
        when(workspaceStore.findById(WORKSPACE_ID)).thenReturn(Optional.of(WORKSPACE));
        when(dreamerEngine.consolidate(WORKSPACE))
                .thenReturn(DreamerCycleSummary.builder().workspace(WORKSPACE).subjectsWalked(0).build());

        job.execute(jobContextWith(WORKSPACE_ID, workspaceStore, dreamerEngine));

        verify(dreamerEngine).consolidate(eq(WORKSPACE));
    }

    @Test
    @DisplayName("missing workspaceId in JobDataMap → JobExecutionException")
    void rejectsMissingWorkspaceId() {
        assertThatThrownBy(() -> job.execute(jobContextWith(null, workspaceStore, dreamerEngine)))
                .isInstanceOf(JobExecutionException.class).hasMessageContaining("workspaceId");
    }

    @Test
    @DisplayName("blank workspaceId → JobExecutionException")
    void rejectsBlankWorkspaceId() {
        assertThatThrownBy(() -> job.execute(jobContextWith("   ", workspaceStore, dreamerEngine)))
                .isInstanceOf(JobExecutionException.class).hasMessageContaining("workspaceId");
    }

    @Test
    @DisplayName("WorkspaceStore missing from scheduler context → JobExecutionException")
    void rejectsMissingWorkspaceStore() {
        assertThatThrownBy(() -> job.execute(jobContextWith(WORKSPACE_ID, null, dreamerEngine)))
                .isInstanceOf(JobExecutionException.class).hasMessageContaining("WorkspaceStore");
    }

    @Test
    @DisplayName("DreamerEngine missing from scheduler context → JobExecutionException")
    void rejectsMissingEngine() {
        assertThatThrownBy(() -> job.execute(jobContextWith(WORKSPACE_ID, workspaceStore, null)))
                .isInstanceOf(JobExecutionException.class).hasMessageContaining("DreamerEngine");
    }

    @Test
    @DisplayName("workspace not found → JobExecutionException, engine not called")
    void rejectsUnknownWorkspace() {
        when(workspaceStore.findById(WORKSPACE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> job.execute(jobContextWith(WORKSPACE_ID, workspaceStore, dreamerEngine)))
                .isInstanceOf(JobExecutionException.class).hasMessageContaining("Workspace not found");

        verify(dreamerEngine, never()).consolidate(eq(WORKSPACE));
    }

    @Test
    @DisplayName("engine throws → wrapped in JobExecutionException")
    void wrapsEngineException() {
        when(workspaceStore.findById(WORKSPACE_ID)).thenReturn(Optional.of(WORKSPACE));
        when(dreamerEngine.consolidate(WORKSPACE)).thenThrow(new RuntimeException("LLM down"));

        Throwable thrown = catchThrowable(() -> job.execute(jobContextWith(WORKSPACE_ID, workspaceStore, dreamerEngine)));

        assertThat(thrown).isInstanceOf(JobExecutionException.class).hasMessageContaining(WORKSPACE_ID);
        assertThat(thrown.getCause()).isInstanceOf(RuntimeException.class).hasMessage("LLM down");
    }

    private static Throwable catchThrowable(ThrowingRunnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static JobExecutionContext jobContextWith(String workspaceId, WorkspaceStore store, DreamerEngine engine)
            throws SchedulerException {
        JobDataMap dataMap = new JobDataMap();
        if (workspaceId != null) {
            dataMap.put(DreamerJob.DATA_KEY_WORKSPACE_ID, workspaceId);
        }

        SchedulerContext schedulerContext = new SchedulerContext();
        if (store != null) {
            schedulerContext.put(DreamerJob.CONTEXT_KEY_WORKSPACE_STORE, store);
        }
        if (engine != null) {
            schedulerContext.put(DreamerJob.CONTEXT_KEY_DREAMER_ENGINE, engine);
        }

        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getContext()).thenReturn(schedulerContext);

        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getMergedJobDataMap()).thenReturn(dataMap);
        when(ctx.getScheduler()).thenReturn(scheduler);
        return ctx;
    }
}
